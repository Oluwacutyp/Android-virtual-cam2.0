package com.vcamstudio.engine.output

import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * App-facing recorder orchestration: owns file lifecycle, the single
 * [RecordingSession], and the encoder-surface handoff. All heavy work runs on
 * a dedicated executor; callbacks arrive on the main thread.
 */
class RecordingController {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Recording(val output: RecordingOutput, val startedAtMs: Long) : State
        data object Stopping : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    var lastRecording: RecordingOutput? = null
        private set

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vcam-rec-ctl") }
    private val main = Handler(Looper.getMainLooper())

    /** Round-30: app-side sink for RECORDER_STATE lines (Timber/dump). */
    var eventSink: ((String) -> Unit)? = null

    @Volatile
    private var session: RecordingSession? = null

    val isBusy: Boolean
        get() = _state.value !is State.Idle

    /**
     * Starts a recording. [onSurfaceReady] (main thread) hands the encoder
     * input surface to the render engine; [onFailed] (main thread) reports
     * codec/setup failures. Returns false when a recording is already active.
     */
    fun start(
        output: RecordingOutput,
        width: Int,
        height: Int,
        fps: Int = 30,
        onSurfaceReady: (Surface) -> Unit,
        onFailed: (String) -> Unit,
    ): Boolean {
        if (isBusy) return false
        _state.value = State.Starting
        executor.execute {
            try {
                // Round-30 mandated order: construct (configure + input
                // surface, state CONFIGURED) -> attach surface to the
                // renderer (onSurfaceReady) -> session.start() (STARTED).
                // createInputSurface() after codec.start() (the r29 bug)
                // threw "valid only at Configured state".
                val s = RecordingSession(
                    output, width, height, fps,
                    VIDEO_BITRATE_BPS, AUDIO_SAMPLE_RATE, AUDIO_BITRATE_BPS,
                ) { msg -> main.post { eventSink?.invoke(msg) } }
                session = s
                lastRecording = output
                main.post {
                    onSurfaceReady(s.inputSurface)
                    try {
                        s.start()
                        _state.value = State.Recording(output, System.currentTimeMillis())
                    } catch (t: Throwable) {
                        session = null
                        _state.value = State.Idle
                        Log.e(TAG, "recorder start failed (codec.start)", t)
                        executor.execute {
                            runCatching { s.stop() }
                        }
                        onFailed(t.message ?: "recorder start failed")
                    }
                }
            } catch (t: Throwable) {
                session = null
                _state.value = State.Idle
                Log.e(TAG, "recorder start failed", t)
                main.post { onFailed(t.message ?: "recorder start failed") }
            }
        }
        return true
    }

    /** Feeds one mixed PCM16 stereo frame into the active session (pump thread). */
    fun offerAudio(pcm: ShortArray) {
        session?.offerAudioPcm(pcm)
    }

    /** Stops asynchronously; [onStopped] (main thread) gets the finalized target or null. */
    fun stop(onStopped: (RecordingOutput?) -> Unit = {}) {
        val s = session ?: return
        if (_state.value !is State.Recording) return
        _state.value = State.Stopping
        executor.execute {
            val out = runCatching { s.stop() }
                .onFailure { Log.e(TAG, "recorder stop failed", it) }
                .getOrNull()
            session = null
            main.post {
                _state.value = State.Idle
                eventSink?.invoke("RECORDER_STATE STOPPED (controller)")
                onStopped(out)
            }
        }
    }

    companion object {
        private const val TAG = "vcam-record"
        private const val VIDEO_BITRATE_BPS = 8_000_000
        const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE_BPS = 128_000
    }
}
