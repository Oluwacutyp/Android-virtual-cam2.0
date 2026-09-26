package com.vcamstudio.app.ai

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
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
