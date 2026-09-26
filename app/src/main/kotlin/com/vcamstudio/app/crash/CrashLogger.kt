package com.vcamstudio.app.crash

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.vcamstudio.app.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Round 42/43 (owner mandates): post-mortems without a PC.
 *
 * R43: the r42 sink (`Android/data/<pkg>/files/crashes/`) is unreachable
 * to file managers on Android 11+ (Android/data is gated), and the field
 * crash still reproduces — so the trace exists but cannot be read. The
 * PRIMARY sink is now the public DCIM tree:
 *
 *   /sdcard/DCIM/VCamStudio/crashes/crash-<epochMs>.txt   (crash dumps)
 *   /sdcard/DCIM/VCamStudio/launch.log                    (boot marker)
 *
 * Written per platform rules (scoped storage):
 *  - API 29+: MediaStore.Files + RELATIVE_PATH (no permission needed).
 *    NOT MediaStore.Images/Video: those collections validate the mime
 *    against media extensions, and .txt would be rejected on-device.
 *  - API <= 28: direct file path (WRITE_EXTERNAL_STORAGE is declared with
 *    maxSdkVersion=28 — the r31 recordings pattern; it must ALSO be
 *    granted at runtime there, which no UI does yet, so on 23-28 this
 *    falls back).
 *  - ANY failure: falls back to the r42 app-specific dir (never crash
 *    the crash handler). Which sink won is recorded IN the file header
 *    (`sink=`) and in logcat (CRASH_LOG_SINK).
 *
 * Boot bookkeeping (CACHE_TOMBSTONE): 7-day sweep of crash files (legacy
 * dir AND MediaStore rows), a WRITETEST file into the crashes dir (the
 * owner can verify reachability in a file manager WITHOUT waiting for a
 * crash), and one launch.log line per boot.
 *
 * Deviation from the r42 mandate SNIPPET (intent preserved): the snippet
 * re-read `Thread.getDefaultUncaughtExceptionHandler()` INSIDE the
 * handler — after installation that returns the handler itself, i.e.
 * infinite recursion instead of a report. We delegate to the PREVIOUS
 * handler captured before install. Platform dialog + process death are
 * preserved.
 *
 * Residual limit (no Java handler crosses it): NATIVE deaths
 * (SIGSEGV/SIGABRT in JNI) leave NO crash file anywhere — absence of a
 * file in DCIM plus a dead process is itself the native-layer diagnosis.
 */
object CrashLogger {

    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** MediaStore RELATIVE_PATH constants — canonical form keeps the trailing slash. */
    private const val CRASH_DIR_REL = "DCIM/VCamStudio/crashes/"
    private const val LAUNCH_DIR_REL = "DCIM/VCamStudio/"
    private const val LAUNCH_NAME = "launch.log"

    /** The public DCIM crashes dir (direct-path form; API <= 28 sink and docs). */
    fun dcimCrashDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "VCamStudio/crashes",
    )

    /** FALLBACK sink (r42): app-specific external dir, readable via adb/SAF only. */
    fun crashDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) File(external, "crashes") else File(context.filesDir, "crashes")
    }

    /** FALLBACK launch log (r42). */
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
        Log.i("vcam-engine", "CRASH_LOGGER_INSTALL dcim=${dcimCrashDir().absolutePath} fallback=${crashDir(context).absolutePath}")
    }

    /** Boot bookkeeping: sweeps + writetest + launch marker. Never throws. */
    fun onAppStart(context: Context) {
        try {
            sweepLegacy(context)
            sweepDcimRows(context)

            // R43 self-test: exercise the exact production sink path at
            // boot. The file is KEPT so the owner gets file-manager proof
            // without a crash; the 7-day sweep cleans old ones.
            val ok = writeToDcim(
                context,
                CRASH_DIR_REL,
                "writetest-${System.currentTimeMillis()}.txt",
                "VCamStudio crash-sink writetest\nts=${System.currentTimeMillis()} " +
                    "version=${BuildConfig.VERSION_NAME} — safe to ignore (auto-swept after 7 days)\n",
            )
            Log.i("vcam-engine", "CACHE_TOMBSTONE selftest dcim=$ok")

            val marker =
                "ts=${System.currentTimeMillis()} version=${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}\n"
            val launchOk = appendDcimLaunchLog(context, marker)
            Log.i("vcam-engine", "CACHE_TOMBSTONE launch dcim=$launchOk")
        } catch (t: Throwable) {
            Log.w("vcam-engine", "CACHE_TOMBSTONE_FAIL", t)
        }
    }

    // ------------------------------------------------------------ crash write

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val name = "crash-${System.currentTimeMillis()}.txt"

        // Primary sink: public DCIM (MediaStore on 29+, direct path below).
        if (writeToDcim(context, CRASH_DIR_REL, name, crashText(context, thread, throwable, "DCIM/VCamStudio/crashes/$name"))) {
            Log.i("vcam-engine", "CRASH_LOG_SINK sink=dcim file=$name")
            return
        }
        // Fallback sink: r42 app-specific dir (better than nothing).
        val fb = crashDir(context)
        fb.mkdirs()
        val f = File(fb, name)
        f.writeText(crashText(context, thread, throwable, "fallback:${f.absolutePath}"))
        Log.i("vcam-engine", "CRASH_LOG_SINK sink=fallback file=${f.absolutePath}")
    }

    private fun crashText(context: Context, thread: Thread, throwable: Throwable, sink: String): String =
        "app=VCamStudio version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.SDK_INT}\n" +
            "sink=$sink\n" +
            "at=${System.currentTimeMillis()}\n" +
            "thread=${thread.name}\n" +
            Log.getStackTraceString(throwable) +
            "\n\n---- recent engine log (last ${RingLog.size()} lines) ----\n" +
            RingLog.dump()

    // ------------------------------------------------------------ DCIM sinks

    /**
     * Write a text file under [relPath] in the public DCIM tree.
     * API 29+: MediaStore.Files (RELATIVE_PATH + IS_PENDING), no permission.
     * API <= 28: direct File path (needs WRITE_EXTERNAL_STORAGE granted).
     * Returns false on ANY failure (caller falls back) — never throws.
     */
    private fun writeToDcim(context: Context, relPath: String, displayName: String, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: return false
                try {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } ?: return false
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    true
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) } // no pending husks left behind
                    Log.w("vcam-engine", "CRASH_LOG_DCIM_WRITE_FAIL name=$displayName", t)
                    false
                }
            } else {
                @Suppress("DEPRECATION") // pre-Q only; direct path is the documented <29 route
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val dir = File(base, relPath.removePrefix("DCIM/").removeSuffix("/"))
                dir.mkdirs()
                File(dir, displayName).writeText(content)
                true
            }
        } catch (t: Throwable) {
            Log.w("vcam-engine", "CRASH_LOG_DCIM_INSERT_FAIL name=$displayName", t)
            false
        }
    }

    /** Append one line to DCIM/VCamStudio/launch.log ("wa" mode on an owned row). */
    private fun appendDcimLaunchLog(context: Context, line: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val existing = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(LAUNCH_DIR_REL, LAUNCH_NAME),
                    null,
                )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

                if (existing != null) {
                    try {
                        resolver.openFileDescriptor(existing, "wa")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { fos ->
                                fos.write(line.toByteArray(Charsets.UTF_8))
                                fos.flush()
                            }
                        } ?: return false
                        return true
                    } catch (t: Throwable) {
                        Log.w("vcam-engine", "CRASH_LOG_LAUNCH_APPEND_FAIL — inserting fresh row", t)
                    }
                }
                writeToDcim(context, LAUNCH_DIR_REL, LAUNCH_NAME, line)
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val dir = File(base, "VCamStudio")
                dir.mkdirs()
                File(dir, LAUNCH_NAME).appendText(line)
                true
            }
        } catch (t: Throwable) {
            Log.w("vcam-engine", "CRASH_LOG_LAUNCH_FAIL", t)
            false
        }
    }

    // ------------------------------------------------------------ sweeps

    /** 7-day sweep of the r42 fallback dir (legacy File API). */
    private fun sweepLegacy(context: Context) {
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
    }

    /** 7-day sweep of DCIM/VCamStudio/crashes/ MediaStore rows (API 29+). */
    private fun sweepDcimRows(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cutoffSec = ((System.currentTimeMillis() - MAX_AGE_MS) / 1000).toString()
            val ids = ArrayList<Long>()
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DATE_MODIFIED}<?",
                arrayOf(CRASH_DIR_REL, cutoffSec),
                null,
            )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            var deleted = 0
            for (id in ids) {
                if (runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                        .getOrDefault(false)
                ) deleted++
            }
            Log.i("vcam-engine", "CACHE_TOMBSTONE dcim_sweep rel=$CRASH_DIR_REL stale=${ids.size} deleted=$deleted")
        } catch (t: Throwable) {
            Log.w("vcam-engine", "CACHE_TOMBSTONE_DCIM_SWEEP_FAIL", t)
        }
    }
}

/**
 * Ring-log Timber tree: keeps the last [CAPACITY] formatted engine lines
 * in RAM so the crash writer can embed "which MODEL_DL_* step was
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
