package com.vcamstudio.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import com.vcamstudio.app.recording.RecordingStore
import timber.log.Timber

@HiltAndroidApp
class StudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("vcam-engine", "VCAM_APP_START ts=${System.currentTimeMillis()}")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Round-31 mandate 3: one-shot migration of app-private recordings
        // into the public library (Movies/VCamStudio). Idempotent — the
        // private folders are empty afterwards.
        Thread({ RecordingStore.migrateExisting(this) }, "vcam-migrate").start()
    }
}
