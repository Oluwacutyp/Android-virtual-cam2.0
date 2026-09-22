package com.vcamstudio.engine.media

import android.util.Log
import androidx.media3.common.audio.TeeAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds decoded video-layer PCM into the mixer MEDIA bus through Media3's
 * TeeAudioProcessor — audio still plays on-device; a copy lands in the bus.
 * Mixer v1 runs at 48 kHz stereo PCM16; any other output format is dropped
 * (logged once) rather than resampled.
 */
@OptIn(UnstableApi::class)
class MixerAudioTap(
    private val onPcm: (pcm: ShortArray, channels: Int, sampleRateHz: Int) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {

    private var formatRate = 0
    private var formatChannels = 0
    private var droppedFormatLogged = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        formatRate = sampleRateHz
        formatChannels = channelCount
        if ((sampleRateHz != 48_000 || channelCount != 2) && !droppedFormatLogged) {
            droppedFormatLogged = true
            Log.w(TAG, "video audio ${sampleRateHz}Hz x$channelCount not fed to mixer (needs 48k stereo)")
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (formatRate != 48_000 || formatChannels != 2) {
            buffer.position(buffer.limit())
            return
        }
        val remaining = buffer.remaining()
        if (remaining <= 0) return
        // PCM bytes on Android are little-endian; media3 ByteBuffers are not.
        val little = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(remaining / 2)
        little.asShortBuffer().get(shorts)
        buffer.position(buffer.limit())
        onPcm(shorts, formatChannels, formatRate)
    }

    companion object {
        private const val TAG = "vcam-media"
    }
}
