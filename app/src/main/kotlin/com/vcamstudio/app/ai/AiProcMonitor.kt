package com.vcamstudio.app.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.vcamstudio.engine.aiface.FaceBox

/**
 * Round 47 (owner mandate): ORT process-isolation watchdog — MAIN process.
 *
 * OrtEnvironment.createSession has natively killed the app on two ORT
 * versions with the same signature: no Kotlin trace, no crash file,
 * instant process death. Whatever process touches libonnxruntime.so can
 * die — so the main process never touches it. [probe]:
 *
 *  1. writes the mandate's marker `ai_proc_probe.tmp` (main-process pid),
 *  2. starts [AiInferenceService] in the `:ai` process with the model path,
 *  3. polls the child's report file (`ai_proc_state.tmp`, written by the
 *     child: pid/state/model/ts) + `/proc/<pid>` liveness every 2 s for
 *     the first 30 s (the mandate's "simpler" detection option — no
 *     binder, no receiver quirks),
 *  4. child died inside the window -> AI_PROC_CRASHED, STATE=dead,
 *     deathEvents fires (the VM toasts). The main process SURVIVES —
 *     that is the whole win.
 *
 * The `ts` on every report discards stale files from previous probes.
 * Diagnostics reads [dumpSection] (AI_PROC_STATE/PID/LAST/MODEL).
 */
object AiProcMonitor {

    enum class State { IDLE, RUNNING, DEAD }

    /** Shared with AiInferenceService (same package, same uid, same filesDir). */
    const val PROBE_FILE = "ai_proc_probe.tmp"
    const val REPORT_FILE = "ai_proc_state.tmp"

    private const val WATCH_WINDOW_MS = 30_000L
    private const val POLL_MS = 2_000L

    data class Report(
        val pid: Int,
        val state: String,
        val model: String?,
        val ts: Long,
        val detail: String?,
    )

    @Volatile private var state: State = State.IDLE
    @Volatile private var pid: Int = -1
    @Volatile private var lastEventMs: Long = 0L
    @Volatile private var modelPath: String? = null
    @Volatile private var detail: String? = null
    @Volatile private var probeStartMs: Long = 0L
    @Volatile private var appContext: Context? = null

    /** Fires once per observed child death — the VM turns it into a toast. */
    private val _deathEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val deathEvents: SharedFlow<Unit> = _deathEvents

    /** Diagnostics block (owner mandate 5 + r48 child-log tail + r49 IPC). */
    fun dumpSection(): String = buildString {
        append("AI_PROC_STATE=").append(state.name.lowercase())
        append("\nAI_PROC_PID=").append(if (pid > 0) pid.toString() else "-")
        append("\nAI_PROC_LAST=").append(lastEventMs)
        append("\nAI_PROC_MODEL=").append(modelPath ?: "-")
        detail?.let { append("\nAI_PROC_DETAIL=").append(it) }
        // Round 49: IPC health — bind state, frame/results counters, last
        // measured latencies and ring geometry (mandate 2.9).
        append("\nAI_IPC_BIND=").append(if (ipcBound.get()) "bound" else "unbound")
        append("\nAI_FRAMES_SUBMITTED=").append(framesSubmitted.get())
        append("\nAI_FRAMES_DROPPED=").append(framesDropped.get())
        append("\nAI_RESULTS_RECEIVED=").append(resultsReceived.get())
        append("\nAI_IPC_RTT_MS=").append(lastRttMs.get())
        append("\nAI_PRE_MS=").append(lastPreMs.get())
        append("\nAI_INFER_MS=").append(lastInferMs.get())
        append("\nAI_RING=frames=")
        append(AiDetectorClient.FRAME_SLOTS).append('x').append(AiDetectorClient.FRAME_PAYLOAD_BYTES)
        append(" boxes=")
        append(AiDetectorClient.BOX_SLOTS).append('x').append(AiDetectorClient.BOX_PAYLOAD_BYTES)
        // Round 50-A0.2: death context — system memory at bind/death and
        // the child's scheduler importance while bound. Separates LMKD
        // (avail < threshold at death) from an OEM/policy kill (plenty of
        // RAM, importance was SERVICE-class and the child died anyway).
        append("\nAI_SYS_BIND_AVAIL_MB=").append(sysBindAvailMb)
        append("\nAI_SYS_DEATH_AVAIL_MB=").append(sysDeathAvailMb)
        append("\nAI_SYS_THRESHOLD_MB=").append(sysThresholdMb)
        append("\nAI_SYS_DEATH_LOW=").append(sysDeathLow)
        append("\nAI_CHILD_IMPORTANCE=").append(childImportance)
        // Round 48: the :ai child's durable step lines (SCRFD_MODEL_FILE,
        // SCRFD_EP, ONNX_SESSION_OK / SCRFD_CREATE_FAIL ...) — written to
        // disk BEFORE ORT is touched, so they survive the child's native
        // abort and name the exact step that died.
        val ctx = appContext
        if (ctx != null) {
            // r51: the 1 Hz heartbeat fills a flat 24-line tail within ~25 s
            // of child life, pushing the boot-time SCRFD_/ONNX_ lines out of
            // the dump. Read a wider window (40), keep at most the last two
            // heartbeats, then take the final 24.
            val wide = AiChildLogTree.tail(ctx, 40)
            val keepHb = wide.withIndex()
                .filter { it.value.contains("AI_HEARTBEAT") }
                .map { it.index }
                .takeLast(2)
                .toSet()
            wide.filterIndexed { i, line -> i in keepHb || !line.contains("AI_HEARTBEAT") }
                .takeLast(24)
                .forEach { line -> append("\nAI_PROC_CHILD_LOG=").append(line) }
        }
    }

    /**
     * Mandate step 3/4: marker -> startService -> 30 s liveness watch.
     * Called from a background thread in Application; nothing blocks the
     * main thread and nothing here touches ORT.
     */
    fun probe(context: Context, model: String) {
        val app = context.applicationContext
        appContext = app
        noteProbeStart(model)
        Thread({
            runCatching {
                File(app.filesDir, PROBE_FILE).writeText(
                    "pid=${Process.myPid()}\nts=${System.currentTimeMillis()}\n",
                )
                app.startService(
                    Intent(app, AiInferenceService::class.java).putExtra("model_path", model),
                )
            }.onFailure { Timber.w(it, "AI_PROC_START_FAIL") }

            val deadline = System.currentTimeMillis() + WATCH_WINDOW_MS
            var lastKnownPid = -1
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                val rep = readReport(app) ?: continue
                lastKnownPid = rep.pid
                when (rep.state) {
                    "ok" -> {
                        noteRunning(rep.pid)
                        return@Thread
                    }
                    "fail" -> {
                        noteDead(rep.pid, "session failed (Java): ${rep.detail ?: "unknown"}")
                        return@Thread
                    }
                    "stopped" -> {
                        noteIdle(rep.pid, "stopped cleanly")
                        return@Thread
                    }
                    "started" -> if (!pidAlive(rep.pid)) {
                        // r50-A0.3: "native abort" was an overclaim — the
                        // A0.2 dump proved the child dies BEFORE touching
                        // ORT (avail 3.6 GB, LOW=false: not LMKD either).
                        noteDead(rep.pid, "child died without Java trace — cause unnamed (read AI_PROC_CHILD_LOG)")
                        return@Thread
                    }
                }
            }
            // Window over: surviving the observation window counts as running.
            if (lastKnownPid > 0 && pidAlive(lastKnownPid)) noteRunning(lastKnownPid)
        }, "vcam-ai-probe-watch").start()
    }

    // ============================================================ r49: IPC

    /** One inference result that came back from the :ai child. */
    data class RemoteResult(
        val frameId: Long,
        val box: FaceBox?,
        val preprocessMs: Long,
        val inferMs: Long,
        val rttMs: Long,
    )

    private val _bound = MutableStateFlow(false)

    /** True while the main process holds a live IAiDetector binder. */
    val bound: StateFlow<Boolean> = _bound

    /** Results demuxed by [AiDetectorClient] — the VM collects these. */
    private val _remoteResults = MutableSharedFlow<RemoteResult>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val remoteResults: SharedFlow<RemoteResult> = _remoteResults

    private val ipcBound = AtomicBoolean(false)
    private val framesSubmitted = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val resultsReceived = AtomicLong(0)
    private val lastRttMs = AtomicLong(-1)
    private val lastPreMs = AtomicLong(-1)
    private val lastInferMs = AtomicLong(-1)

    @Volatile private var client: AiDetectorClient? = null

    fun isBound(): Boolean = _bound.value

    /**
     * Create the client and bind to [AiInferenceService] (BIND_AUTO_CREATE —
     * starts :ai on demand). Dev-toggle-on-at-boot only; nothing here touches
     * ORT.
     */
    fun bind(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (client != null) return
        client = AiDetectorClient(app).also { it.start() }
    }

    /** Release the binding (the service may stay alive via the other bind). */
    fun unbind() {
        client?.shutdown()
        client = null
        analyzerInstance = null
    }

    fun setModelPath(path: String?) {
        client?.setModelPath(path)
        if (path != null) modelPath = path
    }

    /**
     * The ImageAnalysis.Analyzer for the camera pipeline (a class, never a
     * SAM lambda — r39 compiler hang). Created once per client and cached;
     * returns null while unbound: the VM attaches it only when the whole
     * chain is up.
     */
    @Volatile private var analyzerInstance: AiFrameAnalyzer? = null

    fun analyzer(): AiFrameAnalyzer? {
        client ?: return null
        return analyzerInstance ?: AiFrameAnalyzer { payload, w, h, rot, frameId, len ->
            onFrame(payload, w, h, rot, frameId, len)
        }.also { analyzerInstance = it }
    }

    /** Analyzer sink -> client submit (5 Hz gate + ring publish inside). */
    fun onFrame(payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long, length: Int) {
        client?.submit(payload, width, height, rotationDeg, frameId, length)
    }

    // -- callbacks from AiDetectorClient (main process, binder threads) --

    fun noteBound(b: Boolean) {
        ipcBound.set(b)
        _bound.value = b
    }

    fun noteFrameSubmitted() {
        framesSubmitted.incrementAndGet()
    }

    fun noteFrameDropped() {
        framesDropped.incrementAndGet()
    }

    /** r52a: keypoints from the :ai child (upright pixels) for FACE_KPS. */
    data class RemoteKps(
        val frameId: Long,
        val kps: FloatArray,
        val uprightW: Int,
        val uprightH: Int,
    )

    private val _remoteKps = MutableSharedFlow<RemoteKps>(
        replay = 1, extraBufferCapacity = 4,
    )
    val remoteKps: SharedFlow<RemoteKps> = _remoteKps

    fun noteRemoteKps(frameId: Long, kps: FloatArray, uprightW: Int, uprightH: Int) {
        _remoteKps.tryEmit(RemoteKps(frameId, kps, uprightW, uprightH))
    }

    /** r52a debug: ask the child to run the fp16/stub model probes. */
    fun requestModelProbes() {
        runCatching { client?.requestModelProbes() }
    }

    /** r52a debug: ask the child to dump the next face's align crops. */
    fun requestDebugCrops() {
        runCatching { client?.requestDebugCrops() }
    }

    fun noteResult(frameId: Long, box: FaceBox?, preMs: Long, inferMs: Long, rttMs: Long) {
        resultsReceived.incrementAndGet()
        lastRttMs.set(rttMs)
        lastPreMs.set(preMs)
        lastInferMs.set(inferMs)
        _remoteResults.tryEmit(RemoteResult(frameId, box, preMs, inferMs, rttMs))
    }

    /** Instant death signal (linkToDeath); the r47 /proc poll stays as backstop. */
    fun noteChildDeath(reason: String) {
        Log.w("vcam-ai", "AI_PROC_CHILD_DEATH $reason")
        // r50-A0.4: drop the badge too — only onState changed it before, so
        // SCRFD_STATE read RUNNING after a dead child (A0.3 dump).
        _childPhase.value = 0
        noteDead(-1, reason)
    }

    /** Child reported state=running over the callback channel. */
    fun noteChildRunning() {
        // Mirror the r47 file-report state so STATE=running appears even
        // before the poll window closes.
        state = State.RUNNING
        lastEventMs = System.currentTimeMillis()
    }

    /**
     * Round 50: the child's AIDL onState (0 idle, 1 model-missing,
     * 2 session-failed, 3 running). The VM maps it onto
     * FaceDetectionController.Phase so the SCRFD badge is honest for the
     * non-running states too (r49 only surfaced state 3).
     */
    private val _childPhase = MutableStateFlow(-1)
    val childPhase: StateFlow<Int> = _childPhase

    fun noteChildState(childState: Int) {
        _childPhase.value = childState
        if (childState == 3) noteChildRunning()
    }

    // ============================================================ r50-A0.2: death context

    /** System memory at bind — LMKD discrimination (no permissions needed). */
    @Volatile private var sysBindAvailMb: Long = -1
    @Volatile private var sysDeathAvailMb: Long = -1
    @Volatile private var sysThresholdMb: Long = -1
    @Volatile private var sysDeathLow: Boolean = false

    /**
     * The child's scheduler importance while bound — RunningAppProcessInfo
     * lists the CALLING app's own processes (same uid), so the :ai child is
     * visible by name. Values: 100 foreground, 200 visible, 300 service,
     * 400 cached, 1000 gone. A bound-by-foreground child should read ~300;
     * a death at 300 with plenty of RAM points at an OEM/policy killer.
     */
    @Volatile private var childImportance: Int = -1

    /** Called once from onServiceConnected (child exists, still alive). */
    fun noteBindContext(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sysBindAvailMb = mi.availMem / 1_048_576L
            sysThresholdMb = mi.threshold / 1_048_576L
            am.runningAppProcesses
                ?.firstOrNull { it.processName.endsWith(":ai") }
                ?.let {
                    childImportance = it.importance
                    Timber.i(
                        "AI_CHILD_IMPORTANCE importance=%d proc=%s bindAvail=%dMB",
                        it.importance, it.processName, sysBindAvailMb,
                    )
                }
        }
    }

    /** Called from the death recipient — the LAST sysmem reading. */
    fun noteDeathContext() {
        runCatching {
            val ctx = appContext ?: return
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sysDeathAvailMb = mi.availMem / 1_048_576L
            sysDeathLow = mi.lowMemory
            Timber.w(
                "AI_DEATH_SYSMEM avail=%dMB threshold=%dMB low=%s importanceAtBind=%d",
                sysDeathAvailMb, sysThresholdMb, sysDeathLow, childImportance,
            )
        }
    }

    // ------------------------------------------------------------ internals

    private fun noteProbeStart(model: String) {
        val now = System.currentTimeMillis()
        probeStartMs = now
        lastEventMs = now
        modelPath = model
        pid = -1
        detail = null
        state = State.IDLE
        Timber.i("AI_PROC_PROBE_START model=%s", model)
    }

    private fun noteRunning(childPid: Int) {
        pid = childPid
        state = State.RUNNING
        lastEventMs = System.currentTimeMillis()
        detail = null
        Timber.i("AI_PROC_RUNNING pid=%d session survived the crash window", childPid)
    }

    private fun noteIdle(childPid: Int, why: String) {
        pid = childPid
        state = State.IDLE
        lastEventMs = System.currentTimeMillis()
        detail = why
        Timber.i("AI_PROC_IDLE pid=%d %s", childPid, why)
    }

    private fun noteDead(childPid: Int, why: String) {
        pid = childPid
        state = State.DEAD
        lastEventMs = System.currentTimeMillis()
        detail = why
        Timber.e("AI_PROC_CRASHED pid=%d %s", childPid, why)
        _deathEvents.tryEmit(Unit)
    }

    /** Same-uid /proc visibility: another process of OUR app is visible. */
    private fun pidAlive(p: Int): Boolean = File("/proc/$p").exists()

    private fun readReport(context: Context): Report? = runCatching {
        val f = File(context.filesDir, REPORT_FILE)
        if (!f.exists()) return null
        var rPid = -1
        var rState = ""
        var rModel: String? = null
        var rTs = 0L
        var rDetail: String? = null
        f.readLines().forEach { line ->
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            when (k) {
                "pid" -> rPid = v.toIntOrNull() ?: -1
                "state" -> rState = v
                "model" -> rModel = v.takeIf { it != "-" }
                "ts" -> rTs = v.toLongOrNull() ?: 0L
                "detail" -> rDetail = v
            }
        }
        if (rPid <= 0 || rState.isEmpty()) return null
        // Stale report from an earlier probe -> ignore.
        if (rTs < probeStartMs - 1_500) return null
        Report(rPid, rState, rModel, rTs, rDetail)
    }.getOrNull()
}
