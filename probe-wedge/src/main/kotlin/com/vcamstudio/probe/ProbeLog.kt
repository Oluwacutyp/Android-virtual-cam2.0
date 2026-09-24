package com.vcamstudio.probe

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Probe log: every line is appended to filesDir/probe-wedge-log.txt AND to
 * logcat. The file is the deliverable the owner screenshots/exports after a
 * fresh install — it must survive without adb.
 */
class ProbeLog(context: Context) {

    private val file = File(context.filesDir, "probe-wedge-log.txt")
    private val lock = Any()

    init {
        // keep old file so a fresh install is provable by its absence
    }

    fun clear() {
        synchronized(lock) {
            try {
                file.writeText("probe-wedge log\n")
            } catch (t: Throwable) {
                Log.e(TAG, "probe log clear failed", t)
            }
        }
    }

    fun add(line: String) {
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                file.appendText("${System.currentTimeMillis()} $line\n")
            } catch (t: Throwable) {
                Log.e(TAG, "probe log append failed", t)
            }
        }
    }

    fun flush() {
        // appendText is unbuffered per call — nothing extra to do.
    }

    /** Last n lines, for the on-screen mirror. */
    fun tail(n: Int): String = synchronized(lock) {
        try {
            file.readLines().takeLast(n).joinToString("\n")
        } catch (t: Throwable) {
            "<log read failed: ${t.message}>"
        }
    }

    /** Whole file — on-screen mirror + COPY LOG button (verbatim posting). */
    fun all(): String = synchronized(lock) {
        try {
            file.readText()
        } catch (t: Throwable) {
            "<log read failed: ${t.message}>"
        }
    }

    companion object {
        private const val TAG = "probe-wedge"
    }
}
