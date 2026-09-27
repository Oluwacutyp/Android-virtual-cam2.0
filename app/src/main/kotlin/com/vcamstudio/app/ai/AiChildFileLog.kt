package com.vcamstudio.app.ai

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File

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
 * Truncated at each probe start ([reset] from AiInferenceService.onCreate);
 * append-capped so a chatty child cannot grow it unbounded.
 */
object AiChildFileLog : Timber.Tree() {

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
