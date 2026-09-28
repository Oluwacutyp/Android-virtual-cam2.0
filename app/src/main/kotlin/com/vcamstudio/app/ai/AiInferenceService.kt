package com.vcamstudio.app.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import timber.log.Timber
import java.io.File

/**
 * Round 49: thin shell around [AiDetectorBinder] — ORT lives in the `:ai`
 * child process, session creation on its own "vcam-ai-probe" thread inside
 * the binder (r47 evolution: probe -> hold alive -> r49 real inference).
 *
 * OrtEnvironment.createSession has natively killed two ORT versions with
 * the same signature (no Kotlin stack trace, no crash file, immediate
 * process death) inside libonnxruntime.so. A native abort kills the
 * process it runs in and nothing can catch it from Kotlin — so the
 * mandate's answer: make sure it is never the main process.
 *
 * Protocol (with AiProcMonitor / AiDetectorClient):
 *  - main binds (BIND_AUTO_CREATE) and attachRings hands over two mmap
 *    rings (frames main->:ai, boxes :ai->main); payloads never cross
 *    binder;
 *  - onStartCommand forwards the "model_path" extra to the binder
 *    (startService path kept working);
 *  - this service reports pid/state/model/ts into ai_proc_state.tmp at
 *    started -> ok | fail | stopped (r47 lines byte-for-byte);
 *  - the main process polls the report + /proc liveness for 30 s (backstop;
 *    instant death notice now comes via linkToDeath). A native abort HERE
 *    kills only THIS process.
 */
class AiInferenceService : Service() {

    private companion object {
        const val FG_CHANNEL = "vcam_ai_fg"
        const val FG_NOTIF_ID = 42
    }

    private var binder: AiDetectorBinder? = null

    /** model_path extra that arrived before the first bind (r47 probe order). */
    @Volatile private var pendingStartModel: String? = null
    @Volatile private var lastStartModelPath: String? = null

    override fun onBind(intent: Intent?): IBinder {
        // Reuse the binder across reconnects — the session outlives a
        // main-process unbind/bind cycle.
        val b = binder ?: AiDetectorBinder(this).also { binder = it }
        pendingStartModel?.let { m ->
            b.setModel(m)
            pendingStartModel = null
        }
        return b
    }

    override fun onCreate() {
        super.onCreate()
        // Round 48: plant the durable child log HERE (this runs in :ai, before
        // any SCRFD_* line exists) — NOT from StudioApp: the cross-package
        // reference to this package's new symbols fails to resolve on the CI
        // compiler (r48, cause unknown; same-package references resolve fine),
        // and StudioApp never needs this tree anyway.
        if (Timber.forest().none { it === AiChildLogTree }) Timber.plant(AiChildLogTree)
        // Start a fresh durable log BEFORE any step line — the main process's
        // dump reads this file after a native abort.
        AiChildLogTree.reset(this)
        // r54.3-H1: :ai installs its OWN crash handler + breadcrumbs — it
        // must not depend on the main process. (The manifest provider runs
        // in EVERY process of the package, so this is usually a no-op; it
        // guarantees installation even if that ever changes.)
        com.vcamstudio.app.crash.CrashLogger.ensureInstalled(this)
        com.vcamstudio.app.crash.PhaseMark.markAi(this, "proc_start")
        Timber.i("AI_PROC_START pid=%d", Process.myPid())
        // Round 50-A0: memory at birth — distinguishes "native abort" from
        // "the OS killed us" (LMK) when every step line is present but the
        // child is still dead.
        Timber.i(
            "AI_CHILD_MEM max=%dMB free=%dMB",
            Runtime.getRuntime().maxMemory() / 1048576L,
            Runtime.getRuntime().freeMemory() / 1048576L,
        )
        // r54-B1/B4: foreground service at CHILD START (not lazily) so an
        // idle bound-but-not-foreground :ai is no longer the reaper's
        // low-hanging fruit (the dump-1 idle-death signature). Every call
        // is wrapped: an FGS failure degrades to the previous behaviour and
        // logs AI_PROC_FG_FAIL — it must never crash the child.
        var fg = false
        if (!com.vcamstudio.app.transport.DebugFlags.isOn(
                this, com.vcamstudio.app.transport.DebugFlags.KEY_AI_FG,
            )
        ) {
            Timber.i("AI_PROC_FG=off reason=toggle")
        } else {
            runCatching {
                val pm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    pm.createNotificationChannel(
                        android.app.NotificationChannel(
                            FG_CHANNEL, "VCam AI inference",
                            android.app.NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }
                val n = android.app.Notification.Builder(this, FG_CHANNEL)
                    .setSmallIcon(applicationInfo.icon)
                    .setContentTitle("VCam Studio AI")
                    .setContentText("Keeping the inference process alive")
                    .setOngoing(true)
                    .build()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        FG_NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(FG_NOTIF_ID, n)
                }
                fg = true
                Timber.i("AI_PROC_FG=on type=specialUse")
            }.onFailure { t ->
                // B4: degrade, never crash.
                Timber.w("AI_PROC_FG_FAIL=%s", t.message ?: t.javaClass.simpleName)
            }
        }
        fgActive = fg
        writeReport("started", null)
    }

    @Volatile private var fgActive: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra("model_path") ?: return START_NOT_STICKY
        // r49: the binder owns session creation (its vcam-ai-probe thread);
        // the service only forwards the request. If the extra beats the
        // first bind, stash it for onBind.
        lastStartModelPath = modelPath
        val b = binder
        if (b != null) b.setModel(modelPath) else pendingStartModel = modelPath
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Clean teardown only — a native death never reaches this.
        runCatching {
            if (fgActive) {
                stopForeground(true)
                fgActive = false
            }
        }
        binder?.shutdown()
        binder = null
        // r54.3-H2: clean teardown clears :ai's breadcrumb — a native death
        // never reaches this, so the file survives every crash.
        com.vcamstudio.app.crash.PhaseMark.clearAi(this)
        writeReport("stopped", null)
        super.onDestroy()
    }

    /**
     * Report channel to the main process (same uid -> shared filesDir).
     * Format: key=value lines; `ts` lets the watcher discard stale
     * reports from previous probes.
     */
    internal fun writeReport(state: String, detail: String?) {
        runCatching {
            File(filesDir, AiProcMonitor.REPORT_FILE).writeText(
                buildString {
                    append("pid=").append(Process.myPid()).append('\n')
                    // r54.3-H4: the report names its process.
                    append("proc=:ai").append('\n')
                    append("state=").append(state).append('\n')
                    append("model=").append(
                        binder?.lastRequestedModelPath() ?: lastStartModelPath ?: "-",
                    ).append('\n')
                    append("ts=").append(System.currentTimeMillis()).append('\n')
                    // r54-B3: the child's own view of its foreground state.
                    append("fg=").append(if (fgActive) 1 else 0).append('\n')
                    detail?.let { append("detail=").append(it).append('\n') }
                },
            )
        }
    }
}

/**
 * Round 48: file-backed Timber tree for the :ai CHILD process.
 *
 * The r47 crash proof (AI_PROC_STATE=dead) told us the child died after
 * "started" — but its SCRFD_* step lines lived in RAM (RingLog) and died
 * with it, so the main process's Diagnostics dump could never show WHICH
 * step aborted. This tree appends every child log line to
 * filesDir/ai_proc_child.log BEFORE ORT is touched; file writes survive a
 * native abort, and AiProcMonitor.dumpSection surfaces the tail as
 * AI_PROC_CHILD_LOG lines in the owner's dump.
 *
 * Truncated at each probe start ([reset] from onCreate); append-capped so a
 * chatty child cannot grow it unbounded. Lives in THIS file (the
 * RingLog-in-CrashLogger pattern) — the standalone-file form failed to
 * resolve from StudioApp on the CI compiler (r48, cause unknown, evidence
 * byte-perfect on both sides).
 */
object AiChildLogTree : Timber.Tree() {

    private const val FILE_NAME = "ai_proc_child.log"
    // r50-A0.3: 32 KB froze the file after ~15 min of 1 Hz heartbeats (the
    // cap silently drops NEW lines once exceeded — later step lines would
    // vanish from the dump). 256 KB = ~2 h of heartbeat history; the file
    // is app-private and reset every onCreate.
    private const val MAX_BYTES = 256 * 1024
    private val prio = charArrayOf('?', 'V', 'D', 'I', 'W', 'E', 'A')

    @Volatile private var file: File? = null

    /** Wall clock at [reset] — every line is prefixed with [+ms] since it. */
    @Volatile private var epochMs: Long = 0L
    private val lock = Any()

    /** Start a fresh probe log (called from the service, child process). */
    fun reset(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        synchronized(lock) {
            runCatching { f.writeText("") }
            epochMs = System.currentTimeMillis()
            file = f
        }
    }

    /** Last [maxLines] durable child lines (called from the MAIN process). */
    fun tail(context: Context, maxLines: Int): List<String> = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (!f.exists()) emptyList() else f.readLines().takeLast(maxLines)
    }.getOrDefault(emptyList())

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val f = file ?: return
        runCatching {
            synchronized(lock) {
                if (f.length() > MAX_BYTES) return
                // r50-A0.1: per-line age — how long the child LIVED between
                // steps (an instant stop after AI_RING_OPEN reads very
                // differently from a death 3 s into createSession).
                // r54.3-H4: every durable child line is explicitly
                // labelled — a dump tail can never be mistaken for
                // main-process output.
                val head = "proc=:ai [+${System.currentTimeMillis() - epochMs}ms] " +
                    "${prio.getOrElse(priority) { '?' }}/${tag ?: "vcam"}: $message"
                val stack = t?.let { Log.getStackTraceString(it) }
                if (stack.isNullOrBlank()) {
                    f.appendText(head + "\n")
                } else {
                    f.appendText(head + "\n" + stack.split('\n').take(12).joinToString("\n") + "\n")
                }
            }
        }
    }
}
