package com.vcamstudio.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import com.vcamstudio.app.crash.CrashLogger
import com.vcamstudio.app.crash.RingLog
import com.vcamstudio.app.recording.RecordingStore
import timber.log.Timber

@HiltAndroidApp
class StudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Round 42 (owner FIX 1): crash net FIRST — before anything can die.
        // Persists uncaught exceptions to <external>/crashes/ so a device-only
        // owner (no adb) can read the trace from a file manager.
        CrashLogger.install(this)
        Log.i("vcam-engine", "VCAM_APP_START ts=${System.currentTimeMillis()}")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree(), RingLog)
        } else {
            // Release has no logcat access anyway — the ring feeds the crash file.
            Timber.plant(RingLog)
        }
        // Round 42: CACHE_TOMBSTONE events — 7-day crash sweep + launch marker.
        Thread({ CrashLogger.onAppStart(this) }, "vcam-crash-boot").start()
        // Round-31 mandate 3: one-shot migration of app-private recordings
        // into the public library (Movies/VCamStudio). Idempotent — the
        // private folders are empty afterwards.
        Thread({ RecordingStore.migrateExisting(this) }, "vcam-migrate").start()
    }
}
