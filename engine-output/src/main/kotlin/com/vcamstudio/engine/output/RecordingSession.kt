package com.vcamstudio.engine.output

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One MP4 recording: H.264 with surface input (fed by the render engine) +
 * AAC-LC fed by the mixer, muxed via [MediaMuxer].
 *
 * Lifecycle: construct (encoders start) → attach [inputSurface] to the engine
 * as a recording output → [offerAudioPcm] from the mixer pump → [stop] drains
 * both encoders and finalizes the file. Samples produced before the muxer
 * starts (waiting on both track formats) are dropped — sub-second head loss,
 * never a corrupt file.
 */
class RecordingSession(
    val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateBps: Int,
    private val audioSampleRate: Int,
    private val audioBitrateBps: Int,
) {
    private val muxLock = Any()
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1

    @Volatile
    private var muxerStarted = false

    private val videoCodec = createVideoCodec()
    val inputSurface: Surface = videoCodec.createInputSurface()

    private val audioCodec = createAudioCodec()

    @Volatile
    private var stopRequested = false
    private var audioEosQueued = false
    private var totalAudioFrames = 0L

    private val audioPump = LinkedBlockingQueue<ShortArray>()

    private val videoThread = Thread({ drainVideo() }, "vcam-rec-video").apply { start() }
    private val audioThread = Thread({ drainAudio() }, "vcam-rec-audio").apply { start() }

    private fun createVideoCodec(): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun createAudioCodec(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Called from the mixer pump thread: one interleaved stereo PCM16 frame. */
    fun offerAudioPcm(pcm: ShortArray) {
        if (!stopRequested && pcm.isNotEmpty()) audioPump.offer(pcm)
    }

    // ------------------------------------------------------------ video pump

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var eos = false
        try {
            while (!eos) {
                val index = videoCodec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxLock) {
                            videoTrack = muxer.addTrack(videoCodec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                    index >= 0 -> {
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (isEos) eos = true
                        val buf = videoCodec.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            synchronized(muxLock) {
                                if (muxerStarted && videoTrack >= 0) {
                                    muxer.writeSampleData(videoTrack, buf, info)
                                }
                            }
                        }
                        videoCodec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "video drain ended", t)
        }
    }

    // ------------------------------------------------------------ audio pump

    private fun drainAudio() {
        val info = MediaCodec.BufferInfo()
        val inputBuf = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        var eosOut = false
        try {
            while (!eosOut) {
                val pcm = audioPump.poll(10, TimeUnit.MILLISECONDS)
                if (pcm != null) {
                    inputBuf.clear()
                    inputBuf.asShortBuffer().put(pcm)
                    inputBuf.position(0)
                    inputBuf.limit(pcm.size * 2)
                    val idx = audioCodec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val codecBuf = audioCodec.getInputBuffer(idx)!!
                        codecBuf.clear()
                        codecBuf.put(inputBuf)
                        codecBuf.flip()
                        val ptsUs = totalAudioFrames * 1_000_000L / audioSampleRate
                        audioCodec.queueInputBuffer(idx, 0, pcm.size * 2, ptsUs, 0)
                        totalAudioFrames += pcm.size / 2
                    }
                } else if (stopRequested && !audioEosQueued) {
                    audioEosQueued = true
                    val idx = audioCodec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        audioCodec.queueInputBuffer(
                            idx, 0, 0,
                            totalAudioFrames * 1_000_000L / audioSampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                }

                while (true) {
                    val index = audioCodec.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            synchronized(muxLock) {
                                audioTrack = muxer.addTrack(audioCodec.outputFormat)
                                maybeStartMuxer()
                            }
                        }
                        index >= 0 -> {
                            val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val buf = audioCodec.getOutputBuffer(index)
                            if (buf != null && info.size > 0) {
                                synchronized(muxLock) {
                                    if (muxerStarted && audioTrack >= 0) {
                                        muxer.writeSampleData(audioTrack, buf, info)
                                    }
                                }
                            }
                            audioCodec.releaseOutputBuffer(index, false)
                            if (isEos) {
                                eosOut = true
                                break
                            }
                        }
                        else -> break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio drain ended", t)
        }
    }

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            muxer.start()
            muxerStarted = true
            Log.i(TAG, "muxer started (${videoTrack}/${audioTrack})")
        }
    }

    // ---------------------------------------------------------------- stop

    /** Blocks until both encoders drain; returns the finalized file. */
    fun stop(): File {
        stopRequested = true
        runCatching { videoCodec.signalEndOfInputStream() }
        videoThread.join(3_000)
        audioThread.join(3_000)
        if (videoThread.isAlive) videoThread.interrupt()
        if (audioThread.isAlive) audioThread.interrupt()
        runCatching { videoCodec.stop() }
        runCatching { videoCodec.release() }
        runCatching { audioCodec.stop() }
        runCatching { audioCodec.release() }
        synchronized(muxLock) {
            if (muxerStarted) {
                runCatching { muxer.stop() }
                muxerStarted = false
            }
            runCatching { muxer.release() }
        }
        Log.i(TAG, "recording finalized: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    companion object {
        private const val TAG = "vcam-record"
    }
}
