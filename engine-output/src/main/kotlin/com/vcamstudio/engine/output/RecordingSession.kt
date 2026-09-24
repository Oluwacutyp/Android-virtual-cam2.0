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
 * Round-30 (owner "REC FAILS ON INPUT-SURFACE ORDERING") — the MediaCodec
 * lifecycle order is the CONTRACT here. The r29 build called start() inside
 * the constructor and only then createInputSurface(), which throws
 * "setInputSurface() is valid only at Configured state; currently at
 * Running" (framework state check shared by createInputSurface). The fixed
 * order, exactly as mandated:
 *
 *   1. construct: configure(video) -> createInputSurface() -> configure(audio)
 *      [codec state: CONFIGURED; nothing started]
 *   2. controller hands [inputSurface] to the render engine (EGL window)
 *   3. [start()]: codec.start() for both encoders + drain threads
 *   4. frames arrive; encoder produces output
 *   5. [stop()]: signalEndOfInputStream() -> drain -> stop -> release
 *
 * Every transition logs RECORDER_STATE (CONFIGURED / STARTED / STOPPED) via
 * [stateLog] + logcat. Samples produced before the muxer starts (waiting on
 * both track formats) are dropped — sub-second head loss, never a corrupt
 * file.
 */
class RecordingSession(
    val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateBps: Int,
    private val audioSampleRate: Int,
    private val audioBitrateBps: Int,
    private val stateLog: ((String) -> Unit)? = null,
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

    // Round-30: drain threads exist only after codec.start() — dequeue on an
    // un-started codec throws.
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    init {
        stateLog?.invoke("RECORDER_STATE CONFIGURED file=${file.name} ${width}x${height}@${fps}")
        Log.i(TAG, "RECORDER_STATE CONFIGURED")
    }

    /**
     * Step 3 of the mandated order — call AFTER the input surface is attached
     * to the renderer. Starts both encoders and the drain threads.
     */
    fun start() {
        check(!stopRequested) { "session already stopped" }
        videoCodec.start()
        audioCodec.start()
        videoThread = Thread({ drainVideo() }, "vcam-rec-video").apply { start() }
        audioThread = Thread({ drainAudio() }, "vcam-rec-audio").apply { start() }
        stateLog?.invoke("RECORDER_STATE STARTED")
        Log.i(TAG, "RECORDER_STATE STARTED")
    }

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
        // Round-30: configure ONLY. createInputSurface() is legal exclusively
        // in the Configured state; starting here (the r29 bug) made the
        // surface call throw and recording never began.
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
        val vt = videoThread
        val at = audioThread
        vt?.join(3_000)
        at?.join(3_000)
        vt?.takeIf { it.isAlive }?.interrupt()
        at?.takeIf { it.isAlive }?.interrupt()
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
        stateLog?.invoke("RECORDER_STATE STOPPED file=${file.name} bytes=${file.length()}")
        Log.i(TAG, "RECORDER_STATE STOPPED recording finalized: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    companion object {
        private const val TAG = "vcam-record"
    }
}
