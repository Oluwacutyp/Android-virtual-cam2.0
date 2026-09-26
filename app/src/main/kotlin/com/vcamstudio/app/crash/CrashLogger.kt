package com.vcamstudio.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamstudio.app.BuildConfig
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Round 42 (owner mandate, FIX 1): post-mortems without a PC.
 *
 * The owner tests on-device only — no adb, no logcat. An uncaught
 * exception used to leave nothing behind. Now:
 *  - [CrashLogger.install] chains an uncaught-exception handler that
 *    writes thread + stack + the recent [RingLog] engine lines to
 *    `<external files dir>/crashes/crash-<epochMs>.txt`, then falls
 *    through to the platform handler (bug dialog + process death
 *    preserved — the mandate keeps that behaviour).
 *  - [CrashLogger.onAppStart] sweeps crash files older than 7 days and
 *    appends one launch line to `launch.log` (CACHE_TOMBSTONE events,
 *    logged at app start per mandate).
 *
 * One deviation from the mandate SNIPPET, preserving its stated intent
 * ("fall through to the platform handler"): the snippet re-reads
 * `Thread.getDefaultUncaughtExceptionHandler()` INSIDE the handler —
 * after installation that returns the handler itself, i.e. infinite
 * recursion and a StackOverflowError instead of a crash report. We
 * capture the PREVIOUS handler before installing and delegate to that.
 *
 * Residual limit (no Java handler can cross it): NATIVE deaths
 * (SIGSEGV/SIGABRT inside JNI, e.g. in ONNX Runtime session code) kill
 * the process without this handler ever running. Diagnostic therefore:
 * a crash that leaves NO crash-*.txt points at the native layer, and
 * the MODEL_STATE lines in launch-adjacent RingLog output tell us the
 * step (r42 FIX 2) once we can read them.
 */
object CrashLogger {

    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Primary location is the app's external files dir — readable by any
     * file manager without root (the whole point). Falls back to the
     * private dir only if external storage is unmounted.
     */
    fun crashDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) File(external, "crashes") else File(context.filesDir, "crashes")
    }

    fun launchLogFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) File(external, "launch.log") else File(context.filesDir, "launch.log")
    }

    /** Install FIRST in [android.app.Application.onCreate] — before anything can die. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(context, thread, throwable)
            } catch (_: Throwable) {
                // best-effort — never block the death path
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // even the platform handler failing must not mask the write above
            }
        }
        Log.i("vcam-engine", "CRASH_LOGGER_INSTALL dir=${crashDir(context).absolutePath}")
    }

    /** Boot bookkeeping: 7-day crash sweep + launch marker. Never throws. */
    fun onAppStart(context: Context) {
        try {
            val dir = crashDir(context)
            dir.mkdirs()
            val now = System.currentTimeMillis()
            var deleted = 0
            var kept = 0
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (now - f.lastModified() > MAX_AGE_MS && f.delete()) deleted++ else kept++
            }
            Log.i("vcam-engine", "CACHE_TOMBSTONE sweep dir=${dir.absolutePath} deleted=$deleted kept=$kept")

            val marker = launchLogFile(context)
            marker.appendText(
                "ts=$now version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}\n",
            )
            Log.i("vcam-engine", "CACHE_TOMBSTONE launch file=${marker.absolutePath}")
        } catch (t: Throwable) {
            Log.w("vcam-engine", "CACHE_TOMBSTONE_FAIL", t)
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val f = File(dir, "crash-${System.currentTimeMillis()}.txt")
        f.writeText(
            "app=VCamStudio version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}\n" +
                "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.SDK_INT}\n" +
                "at=${System.currentTimeMillis()}\n" +
                "thread=${thread.name}\n" +
                Log.getStackTraceString(throwable) +
                "\n\n---- recent engine log (last ${RingLog.size()} lines) ----\n" +
                RingLog.dump(),
        )
    }
}

/**
 * Ring-log Timber tree: keeps the last [CAPACITY] formatted engine lines
 * in RAM so the crash writer can embed "which MODEL_STATE step was
 * running" into the crash file. Planted in debug AND release — this is
 * the owner's only window into logcat. Thread-safe: vcam-model-dl,
 * vcam-scrfd, main and friends all log.
 */
object RingLog : Timber.Tree() {

    private const val CAPACITY = 400
    private val lines = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val prio = charArrayOf('?', 'V', 'D', 'I', 'W', 'E', 'A')

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        runCatching {
            synchronized(this@RingLog) {
                val head =
                    "${fmt.format(Date())} ${prio.getOrElse(priority) { '?' }}/${tag ?: "vcam"}: $message"
                push(head)
                val stack = t?.let { Log.getStackTraceString(it) }
                if (!stack.isNullOrBlank()) {
                    stack.split('\n').take(20).forEach { l -> push("    $l") }
                }
            }
        }
    }

    private fun push(line: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(ring empty)" else lines.joinToString("\n")
}
