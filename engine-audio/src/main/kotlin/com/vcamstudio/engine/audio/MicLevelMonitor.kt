package com.vcamstudio.engine.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Minimal Phase 1 microphone meter: RMS level 0..1 (with dB-scaled response)
 * for the audio mixer strip. Full routing/DSP arrives with the Phase 1 audio
 * increment; this meter is real capture, not a placeholder animation.
 */
class MicLevelMonitor(
    private val scope: CoroutineScope,
) {
    private val _levelRms = MutableStateFlow(0f)
    val levelRms: StateFlow<Float> = _levelRms.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        if (_running.value) return
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord unavailable")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord create failed", t)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord not initialized (permission?)")
            return
        }
        recorder = record
        record.startRecording()
        _running.value = true

        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(1024)
            while (isActive && _running.value) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buffer[i] / 32768.0
                    sum += s * s
                }
                val rms = sqrt(sum / n)
                // dB-scale response: -60 dB -> 0, 0 dB -> 1
                val db = if (rms > 1e-5) (20.0 * log10(rms)) else -60.0
                val level = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                _levelRms.value = level
            }
        }
    }

    fun stop() {
        _running.value = false
        job?.cancel()
        job = null
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        _levelRms.value = 0f
    }

    companion object {
        private const val TAG = "vcam-audio"
    }
}
