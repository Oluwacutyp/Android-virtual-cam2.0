package com.vcamstudio.engine.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.sqrt

/** The four mixing buses (blueprint §C audio v1). */
enum class AudioBusId { MIC, MEDIA, MUSIC, TTS }

/**
 * 4-bus software mixer v1 (48 kHz stereo, 20 ms frames).
 *
 * Producers push PCM16 from any thread (mic capture, video-layer audio tap);
 * the recorder pulls mixed frames via [read]. MUSIC and TTS faders are wired
 * end-to-end (gain/mute/level/limiter in the signal path) and carry silence
 * until their sources land in Phase 3 — an honest empty bus, not a fake one.
 */
class AudioMixer(
    private val frameFrames: Int = FRAME_FRAMES,
) {

    inner class Bus internal constructor(val id: AudioBusId) {
        private val _gain = MutableStateFlow(1f)
        val gain: StateFlow<Float> = _gain.asStateFlow()
        private val _mute = MutableStateFlow(false)
        val mute: StateFlow<Boolean> = _mute.asStateFlow()
        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        internal val ring = StereoRing(RING_CAPACITY_FRAMES)

        internal fun setGain(v: Float) {
            _gain.value = v.coerceIn(0f, 1.5f)
        }

        internal fun setMute(v: Boolean) {
            _mute.value = v
        }

        internal fun reportLevel(stereo: FloatArray, frames: Int) {
            var sum = 0.0
            for (i in 0 until frames * 2) {
                val s = stereo[i]
                sum += s * s
            }
            val rms = sqrt(sum / (frames * 2).coerceAtLeast(1))
            _level.value = min(1f, (rms * 3f).toFloat())
        }
    }

    private val busMap: Map<AudioBusId, Bus> =
        AudioBusId.entries.associateWith { Bus(it) }

    fun bus(id: AudioBusId): Bus = busMap.getValue(id)

    private val _masterGain = MutableStateFlow(1f)
    val masterGain: StateFlow<Float> = _masterGain.asStateFlow()
    private val _limiterEnabled = MutableStateFlow(true)
    val limiterEnabled: StateFlow<Boolean> = _limiterEnabled.asStateFlow()
    private val _masterLevel = MutableStateFlow(0f)
    val masterLevel: StateFlow<Float> = _masterLevel.asStateFlow()

    val limiter = Limiter(sampleRate = SAMPLE_RATE)

    fun setBusGain(id: AudioBusId, v: Float) = busMap.getValue(id).setGain(v)
    fun setBusMute(id: AudioBusId, v: Boolean) = busMap.getValue(id).setMute(v)
    fun setMasterGain(v: Float) {
        _masterGain.value = v.coerceIn(0f, 1.5f)
    }

    fun setLimiterEnabled(v: Boolean) {
        _limiterEnabled.value = v
    }

    // ---- Round 44 (r33 FIX B): monitor routing. The MASTER feed serves two
    // consumers with different contracts: the RECORDER must contain every
    // bus, while the SPEAKER MONITOR must never carry the MIC (owner's
    // field echo) unless the DEV "monitor mic" toggle is on (headphones).
    // TTS stays monitored — unchanged from pre-r44 behavior.

    @Volatile
    private var monitorMicEnabled = false

    fun setMonitorMic(v: Boolean) {
        monitorMicEnabled = v
    }

    fun monitorsMic(): Boolean = monitorMicEnabled


    /** Producer side: interleaved PCM16, mono or stereo. */
    fun offerPcm(id: AudioBusId, pcm: ShortArray, channels: Int) {
        val bus = busMap.getValue(id)
        if (channels == 2) {
            bus.reportLevel(stereoOf(pcm), pcm.size / 2)
            bus.ring.push(stereoOf(pcm), pcm.size / 2)
        } else {
            val stereo = monoToStereo(pcm)
            bus.reportLevel(stereo, pcm.size)
            bus.ring.push(stereo, pcm.size)
        }
    }

    /**
     * Pulls one mixed frame into [out] (PCM16 stereo interleaved; expected
     * size 2 × [frameFrames]). Buses that underflow contribute silence —
     * recording continues seamlessly over dropped bus frames.
     */
    fun read(out: ShortArray): Int {
        val mix = FloatArray(out.size)
        for (bus in busMap.values) {
            val gain = bus.gain.value * (if (bus.mute.value) 0f else 1f)
            if (gain <= 0f) {
                bus.ring.drop(frameFrames)
                continue
            }
            val frame = bus.ring.pop(frameFrames) ?: continue
            for (i in mix.indices) mix[i] += frame[i] * gain
        }
        if (_limiterEnabled.value) limiter.process(mix)
        val master = _masterGain.value
        var peak = 0f
        for (i in out.indices) {
            val v = (mix[i] * master).coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        _masterLevel.value = peak
        return frameFrames
    }

    /**
     * Round 44 (r33 FIX B): two-output variant of [read]. [out] receives the
     * FULL mix (what the recorder must contain); [monitorOut] receives the
     * MONITOR mix — every bus EXCEPT MIC, unless [setMonitorMic] is on —
     * which is what [com.vcamstudio.engine.audio.MasterMonitor] renders to
     * the speaker. Both are scaled by master gain; the limiter applies to
     * the RECORD path only (it is stateful and must run once per frame).
     * Buses pop once, so this is the single consumer pass — no double read.
     */
    fun readInto(out: ShortArray, monitorOut: ShortArray): Int {
        val mix = FloatArray(out.size)
        val mon = FloatArray(out.size)
        for (bus in busMap.values) {
            val gain = bus.gain.value * (if (bus.mute.value) 0f else 1f)
            if (gain <= 0f) {
                bus.ring.drop(frameFrames)
                continue
            }
            val frame = bus.ring.pop(frameFrames) ?: continue
            val inMonitor = monitorMicEnabled || bus.id != AudioBusId.MIC
            for (i in mix.indices) {
                val s = frame[i] * gain
                mix[i] += s
                if (inMonitor) mon[i] += s
            }
        }
        if (_limiterEnabled.value) limiter.process(mix)
        val master = _masterGain.value
        var peak = 0f
        for (i in out.indices) {
            val v = (mix[i] * master).coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
            val m = (mon[i] * master).coerceIn(-1f, 1f)
            monitorOut[i] = (m * 32767f).toInt().toShort()
        }
        _masterLevel.value = peak
        return frameFrames
    }

    /** Drops buffered audio (record start) so stale tails never enter files. */
    fun reset() {
        busMap.values.forEach { it.ring.clear() }
        limiter.reset()
    }

    private fun monoToStereo(mono: ShortArray): FloatArray {
        val out = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            val s = mono[i] / 32768f
            out[2 * i] = s
            out[2 * i + 1] = s
        }
        return out
    }

    private fun stereoOf(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = pcm[i] / 32768f
        return out
    }

    /** Small synchronized single-producer/single-consumer float ring. */
    internal class StereoRing(capacityFrames: Int) {
        private val buf = FloatArray(capacityFrames * 2)
        private val capacity = capacityFrames * 2
        private var writePos = 0
        private var available = 0

        /** Overwrites the oldest data when the producer outruns the consumer. */
        @Synchronized
        fun push(stereo: FloatArray, frames: Int) {
            val total = (frames * 2).coerceAtMost(capacity)
            val start = stereo.size - total
            for (i in 0 until total) {
                buf[writePos] = stereo[start + i]
                writePos = (writePos + 1) % capacity
                if (available < capacity) available++
            }
        }

        /** Returns frames*2 interleaved floats, or null on underflow. */
        @Synchronized
        fun pop(frames: Int): FloatArray? {
            val need = frames * 2
            if (available < need) return null
            val out = FloatArray(need)
            var readPos = (writePos - available + capacity) % capacity
            for (i in 0 until need) {
                out[i] = buf[readPos]
                readPos = (readPos + 1) % capacity
                available--
            }
            return out
        }

        @Synchronized
        fun drop(frames: Int) {
            available -= (frames * 2).coerceAtMost(available)
        }

        @Synchronized
        fun clear() {
            available = 0
            writePos = 0
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_FRAMES = 960 // 20 ms
        private const val RING_CAPACITY_FRAMES = 4 * FRAME_FRAMES
    }
}
