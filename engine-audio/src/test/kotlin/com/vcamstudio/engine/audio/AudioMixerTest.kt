package com.vcamstudio.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {

    private fun stereoSine(frames: Int, amplitude: Float, periodFrames: Int = 48): FloatArray {
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val v = (amplitude * Math.sin(2.0 * Math.PI * f / periodFrames)).toFloat()
            out[2 * f] = v
            out[2 * f + 1] = v
        }
        return out
    }

    @Test
    fun `single bus passthrough at unity`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        val src = stereoSine(frames, 0.5f)
        // Push via the PCM16 API (0.5 * 32767 ≈ 16383).
        val pcm = ShortArray(src.size) { (src[it] * 32767f).toInt().toShort() }
        mixer.offerPcm(AudioBusId.MIC, pcm, channels = 2)

        val out = ShortArray(frames * 2)
        assertEquals(frames, mixer.read(out))
        // Peak should land near ±0.5 within limiter/quantization tolerance.
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) } / 32767f
        assertTrue("peak=$peak", peak in 0.40f..0.60f)
    }

    @Test
    fun `mute yields silence`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        val pcm = ShortArray(frames * 2) { 16383 }
        mixer.setBusMute(AudioBusId.MEDIA, true)
        mixer.offerPcm(AudioBusId.MEDIA, pcm, channels = 2)

        val out = ShortArray(frames * 2)
        mixer.read(out)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun `underflow contributes silence`() {
        val mixer = AudioMixer()
        val out = ShortArray(AudioMixer.FRAME_FRAMES * 2)
        mixer.read(out)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun `master gain scales the mix`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        mixer.setMasterGain(0.25f)
        val pcm = ShortArray(frames * 2) { 16383 }
        mixer.offerPcm(AudioBusId.MUSIC, pcm, channels = 2)
        val out = ShortArray(frames * 2)
        mixer.read(out)
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) } / 32767f
        assertTrue("peak=$peak", peak in 0.17f..0.34f)
    }

    @Test
    fun `limiter keeps hot mix under ceiling`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        // Two full-scale buses stacked → 2.0 peak pre-limiter.
        val pcm = ShortArray(frames * 2) { 32767 }
        mixer.offerPcm(AudioBusId.MIC, pcm, channels = 2)
        mixer.offerPcm(AudioBusId.MEDIA, pcm, channels = 2)
        val out = ShortArray(frames * 2)
        mixer.read(out)
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) } / 32767f
        assertTrue("peak=$peak exceeded ceiling", peak <= mixer.limiter.ceiling + 0.01f)
        assertTrue("peak=$peak should be limited near threshold", peak >= mixer.limiter.threshold - 0.15f)
    }

    @Test
    fun `ring underflow returns null and drop clears`() {
        val ring = AudioMixer.StereoRing(4)
        assertNull(ring.pop(2))
        ring.push(FloatArray(2 * 2), 2)
        ring.clear()
        assertNull(ring.pop(2))
    }
}
