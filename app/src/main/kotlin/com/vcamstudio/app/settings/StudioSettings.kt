package com.vcamstudio.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vcam_settings")

/** Scene render resolution presets (9:16). */
enum class SceneResolution(val width: Int, val height: Int, val label: String) {
    P_480(480, 854, "480p (light)"),
    P_720(720, 1280, "720p (default)"),
    P_1080(1080, 1920, "1080p (flagship)"),
    ;

    companion object {
        fun fromWidth(width: Int): SceneResolution =
            entries.firstOrNull { it.width == width } ?: P_720
    }
}

/** Persisted studio settings (DataStore). Room arrives with scenes persistence. */
@Singleton
class StudioSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolutionKey = stringPreferencesKey("scene_resolution_width")
    private val scrfdSessionKey = booleanPreferencesKey("dev_scrfd_session")
    private val monitorMicKey = booleanPreferencesKey("dev_monitor_mic")

    val sceneResolution: Flow<SceneResolution> = context.dataStore.data.map { prefs ->
        SceneResolution.fromWidth(prefs[resolutionKey]?.toIntOrNull() ?: SceneResolution.P_720.width)
    }

    suspend fun setSceneResolution(resolution: SceneResolution) {
        context.dataStore.edit { it[resolutionKey] = resolution.width.toString() }
    }

    /**
     * Round 44 (owner decision 1): DEV-only SCRFD session gate, default OFF.
     * Only the value read at PROCESS BOOT drives session creation ("requires
     * restart"); toggling mid-session persists for the next boot. When a boot
     * finds the native-crash sentinel, the app writes false here (auto-disable).
     */
    val scrfdSessionEnabled: Flow<Boolean> = context.dataStore.data.map { it[scrfdSessionKey] ?: false }

    suspend fun setScrfdSessionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[scrfdSessionKey] = enabled }
    }

    /**
     * Round 44 (r33 FIX B): DEV "monitor mic" — route the MIC bus to the
     * speaker monitor too (headphones case). Default OFF: the monitor hears
     * MEDIA + MUSIC + TTS only, so the mic can never echo.
     */
    val monitorMic: Flow<Boolean> = context.dataStore.data.map { it[monitorMicKey] ?: false }

    suspend fun setMonitorMic(enabled: Boolean) {
        context.dataStore.edit { it[monitorMicKey] = enabled }
    }
}
