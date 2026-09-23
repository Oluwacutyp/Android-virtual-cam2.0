package com.vcamstudio.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class StudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("vcam-engine", "VCAM_APP_START ts=${System.currentTimeMillis()}")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
