package com.vcamstudio.app

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import com.vcamstudio.app.ai.AiProcMonitor
import com.vcamstudio.app.crash.CrashLogger
import com.vcamstudio.app.crash.RingLog
import com.vcamstudio.app.recording.RecordingStore
import com.vcamstudio.app.settings.StudioSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File

@HiltAndroidApp
class StudioApp : Application() {

    /** Hilt entry point for the r47 boot probe (Application can't inject). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AiBootEntryPoint {
        fun settings(): StudioSettings
        fun modelManager(): com.vcamstudio.engine.aicore.ModelManager
    }

    override fun onCreate() {
        super.onCreate()
        // Round 42 (owner FIX 1): crash net FIRST — before anything can die.
        // Installed in EVERY process (a Java crash in :ai is diagnosable too;
        // a native one kills :ai before any handler — that's what isolation
        // contains).
        CrashLogger.install(this)
        Log.i("vcam-engine", "VCAM_APP_START ts=${System.currentTimeMillis()} process=${currentProcessName()}")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree(), RingLog)
        } else {
            // Release has no logcat access anyway — the ring feeds the crash file.
            Timber.plant(RingLog)
        }
        if (currentProcessName() == packageName) {
            // ---- main process only ----
            // Round 42: CACHE_TOMBSTONE events — 7-day crash sweep + launch marker.
            Thread({ CrashLogger.onAppStart(this) }, "vcam-crash-boot").start()
            // Round-31 mandate 3: one-shot migration of app-private recordings
            // into the public library (Movies/VCamStudio). Idempotent — the
            // private folders are empty afterwards.
            Thread({ RecordingStore.migrateExisting(this) }, "vcam-migrate").start()
            // Round 47 (owner mandate 3): the ORT probe lives in :ai now.
            startAiProcProbeIfArmed()
        }
    }

    /**
     * Round 47 (owner mandate 3): if the DEV SCRFD toggle is ON at boot,
     * start [AiInferenceService] in the :ai process with the model path.
     * The main process NEVER calls ScrfdDetector / touches OrtEnvironment;
     * nothing here blocks the main thread or loads ORT on it.
     * DEV toggle OFF -> nothing happens.
     */
    private fun startAiProcProbeIfArmed() {
        Thread({
            val entry = EntryPointAccessors.fromApplication(this, AiBootEntryPoint::class.java)
            val enabled = runCatching {
                runBlocking { entry.settings().scrfdSessionEnabled.first() }
            }.getOrDefault(false)
            if (!enabled) return@Thread
            // r44's main-process sentinel is retired — process isolation
            // replaces it (a leftover file is meaningless now).
            runCatching { File(filesDir, "scrfd_session_sentinel").delete() }
            val path = runCatching {
                entry.modelManager().readyFile("scrfd_10g_bnkps")?.absolutePath
            }.getOrNull()
            if (path == null) {
                Timber.w("AI_PROC_SKIP reason=model-not-ready")
                return@Thread
            }
            AiProcMonitor.probe(this, path)
        }, "vcam-ai-probe-boot").start()
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // API 26-27: /proc/self/cmdline holds the (null-terminated) name.
            runCatching {
                File("/proc/self/cmdline").inputStream().use { ins ->
                    ins.readBytes().toString(Charsets.UTF_8).trim('\u0000').trim()
                }
            }.getOrDefault("")
        }
}
