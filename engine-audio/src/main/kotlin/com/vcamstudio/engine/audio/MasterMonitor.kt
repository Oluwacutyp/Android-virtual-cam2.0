package com.vcamstudio.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Round-30 (owner "MIXER MEDIA MUTE BYPASSED"): the audible monitor of the
 * MASTER mix. Before this class the mixer's output reached only the recorder,
 * while video layers played straight to the device speaker via ExoPlayer's
 * own AudioTrack — bus faders/mutes had no audible effect.
 *
 * The single mix pump (app side) calls [write] with every MONITOR mix
 * frame; this class renders it to the device output. write() blocks on the
 * AudioTrack when its buffer is full, which also paces the pump to real time.
 * Returns false when not running so the pump can fall back to timed pacing.
 *
 * Round 44 (r33 FIX B): ROUTING lives at the feed, not here — the pump hands
 * this track the output of [AudioMixer.readInto]'s monitor mix (MEDIA +
 * MUSIC + TTS; MIC excluded unless the DEV "monitor mic" toggle is on), so
 * the mic can never echo through the speaker by construction.
 */
class MasterMonitor(private val sampleRate: Int = AudioMixer.SAMPLE_RATE) {

    private var track: AudioTrack? = null
    private var failed = false

    /** Renders one interleaved PCM16 stereo frame. Blocking when saturated. */
    @Synchronized
    fun write(pcm: ShortArray): Boolean {
        if (failed) return false
        val t = track ?: run {
            val created = createTrack() ?: run {
                failed = true
                return false
            }
            track = created
            created
        }
        val written = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        return written > 0
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(AudioMixer.FRAME_FRAMES * 2 * 4))
            .build()
            .also { it.play() }
    }.getOrNull()

    @Synchronized
    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
