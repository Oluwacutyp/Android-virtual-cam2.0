package com.vcamstudio.engine.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Drives one video layer: decodes with ExoPlayer and renders into an
 * engine-owned external texture surface. Supports loop, mute, volume, speed
 * and trim (clip in/out) — the Phase 1 video-layer contract.
 *
 * All methods must be called on the main thread (ExoPlayer contract).
 */
class VideoLayerController(
    context: Context,
    val sourceId: String,
    audioTap: MixerAudioTap? = null,
) {

    /** Effective display size (pixel-width-ratio applied), reported to the UI. */
    var videoSizePx: Pair<Int, Int> = 0 to 0
        private set

    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    private val player: ExoPlayer = run {
        val renderersFactory = if (audioTap != null) {
            object : DefaultRenderersFactory(context) {
                @OptIn(UnstableApi::class)
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(audioTap)))
                    .build()
            }
        } else {
            DefaultRenderersFactory(context)
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
        addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                val h = videoSize.height.coerceAtLeast(1)
                videoSizePx = w to h
                onVideoSizeChanged?.invoke(w, h)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "VIDEO_ERROR $sourceId", error)
                onError?.invoke(error.message ?: "playback error")
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "VIDEO_FIRST_FRAME $sourceId")
            }
        })
        }
    }

    val durationMs: Long
        get() = player.duration

    val isPlaying: Boolean
        get() = player.isPlaying

    /**
     * Attaches the render surface BEFORE load so the first frame lands in the
     * engine texture, then prepares [uri].
     */
    fun load(
        surface: Surface,
        uri: Uri,
        loop: Boolean = true,
        muted: Boolean = false,
        volume: Float = 1f,
        speed: Float = 1f,
        trimStartMs: Long = 0L,
        trimEndMs: Long = C.TIME_UNSET,
    ) {
        player.setVideoSurface(surface)
        val item = MediaItem.Builder().setUri(uri)
        if (trimStartMs > 0 || trimEndMs != C.TIME_UNSET) {
            item.setClippingConfiguration(
                ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs.coerceAtLeast(0))
                    .setEndPositionMs(trimEndMs)
                    .build(),
            )
        }
        player.setMediaItem(item.build())
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else volume.coerceIn(0f, 1f)
        player.playbackParameters = player.playbackParameters.withSpeed(speed.coerceIn(0.25f, 4f))
        player.prepare()
        player.playWhenReady = true
    }

    fun setLoop(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setMuted(muted: Boolean, lastVolume: Float = 1f) {
        player.volume = if (muted) 0f else lastVolume.coerceIn(0f, 1f)
    }

    fun setVolume(volume: Float, muted: Boolean) {
        player.volume = if (muted) 0f else volume.coerceIn(0f, 1f)
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f))
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun release() {
        runCatching { player.release() }
    }

    companion object {
        private const val TAG = "vcam-media"
    }
}
