package com.vcamstudio.app.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * r54.1-X3 (owner mandate): the camera-start sequence is bracketed by
 * one-line breadcrumbs written to `filesDir/last_phase` (app-private, no
 * permission, file closed on every write).
 *
 * A crash with NO Java stack is a NATIVE fault — no uncaught-exception
 * handler fires and no crash file is written. The breadcrumb is the only
 * localisation: the next launch reads it and logs LAST_PHASE=<phase>,
 * naming the step that never completed. The file is cleared only when a
 * run COMPLETES (StudioViewModel.onCleared), so it survives every crash.
 *
 * Sequence (owner X3): permission_granted -> source_created ->
 * analyzer_bound -> ai_chain_up -> transport_attached -> first_frame.
 * All marks are app-side; the engine modules are untouched (r50 law).
 */
object PhaseMark {
    private const val NAME = "last_phase"

    fun mark(context: Context, phase: String) {
        runCatching {
            File(context.applicationContext.filesDir, NAME).writeText(phase)
        }.onFailure { Log.w("vcam-engine", "PHASE_MARK_FAIL phase=$phase", it) }
    }

    fun read(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, NAME)
        if (f.exists()) f.readText().trim().ifEmpty { null } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, NAME).delete() }
    }
}
