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
 * Round 47 (owner mandate): ORT lives here — the `:ai` child process.
 *
 * OrtEnvironment.createSession has natively killed two ORT versions with
 * the same signature (no Kotlin stack trace, no crash file, immediate
 * process death) inside libonnxruntime.so. A native abort kills the
 * process it runs in and nothing can catch it from Kotlin — so the
 * mandate's answer: make sure it is never the main process.
 *
 * Protocol (with AiProcMonitor, via the shared app-private filesDir):
 *  - main writes ai_proc_probe.tmp (main pid) and calls startService
 *    with the "model_path" extra;
 *  - this service reports pid/state/model/ts into ai_proc_state.tmp at
 *    started -> ok | fail | stopped;
 *  - the main process polls the report + /proc liveness for 30 s. A
 *    native abort HERE kills only THIS process.
 *
 * The session is created on a worker thread ("vcam-ai-probe") — a
 * multi-second createSession must not risk a service ANR. The detector
 * is HELD ALIVE (mandate: "hold the session alive so we can observe the
 * crash"); real inference wiring is round 48; closed in onDestroy.
 */
class AiInferenceService : Service() {

    private var detector: com.vcamstudio.engine.aiface.ScrfdDetector? = null

    @Volatile private var lastModelPath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

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
        Timber.i("AI_PROC_START pid=%d", Process.myPid())
        writeReport("started", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra("model_path") ?: return START_NOT_STICKY
        lastModelPath = modelPath
        Thread({
            try {
                // The call that used to kill the whole app — behind a
                // thread the main process can't observe crashing. If ORT
                // aborts natively, only this process dies.
                detector = com.vcamstudio.engine.aiface.ScrfdDetector(modelPath)
                Timber.i("AI_PROC_SESSION_OK model=%s", modelPath)
                writeReport("ok", null)
            } catch (t: Throwable) {
                Timber.e(t, "AI_PROC_SESSION_FAIL")
                writeReport("fail", t.message)
            }
        }, "vcam-ai-probe").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Clean teardown only — a native death never reaches this.
        runCatching { detector?.close() }
        detector = null
        writeReport("stopped", null)
        super.onDestroy()
    }

    /**
     * Report channel to the main process (same uid -> shared filesDir).
     * Format: key=value lines; `ts` lets the watcher discard stale
     * reports from previous probes.
     */
    private fun writeReport(state: String, detail: String?) {
        runCatching {
            File(filesDir, AiProcMonitor.REPORT_FILE).writeText(
                buildString {
                    append("pid=").append(Process.myPid()).append('\n')
                    append("state=").append(state).append('\n')
                    append("model=").append(lastModelPath ?: "-").append('\n')
                    append("ts=").append(System.currentTimeMillis()).append('\n')
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
    private const val MAX_BYTES = 32 * 1024
    private val prio = charArrayOf('?', 'V', 'D', 'I', 'W', 'E', 'A')

    @Volatile private var file: File? = null
    private val lock = Any()

    /** Start a fresh probe log (called from the service, child process). */
    fun reset(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        synchronized(lock) {
            runCatching { f.writeText("") }
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
                val head = "${prio.getOrElse(priority) { '?' }}/${tag ?: "vcam"}: $message"
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
