package com.vcamstudio.app.ai

import android.content.Context
import android.content.Intent
import android.os.Process
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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

    /** Fires once per observed child death — the VM turns it into a toast. */
    private val _deathEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val deathEvents: SharedFlow<Unit> = _deathEvents

    /** Diagnostics block (owner mandate 5). */
    fun dumpSection(): String = buildString {
        append("AI_PROC_STATE=").append(state.name.lowercase())
        append("\nAI_PROC_PID=").append(if (pid > 0) pid.toString() else "-")
        append("\nAI_PROC_LAST=").append(lastEventMs)
        append("\nAI_PROC_MODEL=").append(modelPath ?: "-")
        detail?.let { append("\nAI_PROC_DETAIL=").append(it) }
    }

    /**
     * Mandate step 3/4: marker -> startService -> 30 s liveness watch.
     * Called from a background thread in Application; nothing blocks the
     * main thread and nothing here touches ORT.
     */
    fun probe(context: Context, model: String) {
        val app = context.applicationContext
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
                        noteDead(rep.pid, "child died without Java trace — native abort (isolated to :ai)")
                        return@Thread
                    }
                }
            }
            // Window over: surviving the observation window counts as running.
            if (lastKnownPid > 0 && pidAlive(lastKnownPid)) noteRunning(lastKnownPid)
        }, "vcam-ai-probe-watch").start()
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
