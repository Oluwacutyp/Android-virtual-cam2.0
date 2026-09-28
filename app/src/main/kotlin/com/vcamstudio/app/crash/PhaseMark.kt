package com.vcamstudio.app.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * r54.1-X3 / r54.3-H2 (owner mandates): one-line breadcrumbs written to
 * app-private files (no permission, closed on every write).
 *
 * TWO files, one per process — filesDir is SHARED between the main and :ai
 * processes, so either process can read both:
 *   last_phase     — the MAIN process camera-start sequence:
 *                    permission_granted -> source_created -> analyzer_bound
 *                    -> ai_chain_up/transport_attached -> first_frame
 *   last_phase_ai  — the :ai child load/infer sequence (content tagged
 *                    "proc=:ai|<phase>"):
 *                    proc_start -> MODEL_FILE_RESOLVE -> SESSION_CREATE
 *                    -> FIRST_INFER -> KPS_DECODE -> RESULT_PUBLISH
 *
 * A crash with NO Java stack is a NATIVE fault — no uncaught-exception
 * handler fires and no crash file is written. The breadcrumb is the only
 * localisation: the next launch reads both files (MainActivity logs
 * LAST_PHASE / AI_LAST_PHASE) and the dump carries AI_CHILD_LAST_PHASE.
 * Files are cleared only when a run COMPLETES (VM.onCleared / service
 * onDestroy), so they survive every crash.
 */
object PhaseMark {
    private const val NAME = "last_phase"
    private const val AI_NAME = "last_phase_ai"

    fun mark(context: Context, phase: String) {
        write(context, NAME, phase)
    }

    /** r54.3-H2: :ai's own file, content tagged with the process name. */
    fun markAi(context: Context, phase: String) {
        write(context, AI_NAME, "proc=:ai|$phase")
    }

    fun read(context: Context): String? = readImpl(context, NAME)

    fun readAi(context: Context): String? = readImpl(context, AI_NAME)

    fun clear(context: Context) {
        clearImpl(context, NAME)
    }

    fun clearAi(context: Context) {
        clearImpl(context, AI_NAME)
    }

    private fun write(context: Context, name: String, content: String) {
        runCatching {
            File(context.applicationContext.filesDir, name).writeText(content)
        }.onFailure { Log.w("vcam-engine", "PHASE_MARK_FAIL file=$name phase=$content", it) }
    }

    private fun readImpl(context: Context, name: String): String? = runCatching {
        val f = File(context.applicationContext.filesDir, name)
        if (f.exists()) f.readText().trim().ifEmpty { null } else null
    }.getOrNull()

    private fun clearImpl(context: Context, name: String) {
        runCatching { File(context.applicationContext.filesDir, name).delete() }
    }
}
