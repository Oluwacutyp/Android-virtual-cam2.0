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
 * Real microphone capture at 48 kHz mono: drives the level meter (dB-scaled)
 * and, when [pcmSink] is set, feeds the audio mixer's MIC bus with 20 ms
 * PCM16 chunks. This is the bus the recorder's AAC track is built from.
 */
class MicLevelMonitor(
    private val scope: CoroutineScope,
) {
    private val _levelRms = MutableStateFlow(0f)
    val levelRms: StateFlow<Float> = _levelRms.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Optional PCM tap: (mono PCM16 samples, sampleCount) at 48 kHz, 20 ms cadence. */
    @Volatile
    var pcmSink: ((ShortArray, Int) -> Unit)? = null

    private var job: Job? = null
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        if (_running.value) return
        val sampleRate = 48_000
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
            val buffer = ShortArray(960) // 20 ms mono @ 48 kHz
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
                pcmSink?.invoke(buffer.copyOf(n), n)
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
