package com.vcamstudio.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiterTest {

    @Test
    fun `quiet signal passes near unity`() {
        val limiter = Limiter()
        val samples = FloatArray(480) { 0.1f }
        limiter.process(samples)
        // Envelope starts at unity and stays there below threshold.
        assertEquals(0.1f, samples[479], 0.01f)
    }

    @Test
    fun `hot signal is capped at ceiling`() {
        val limiter = Limiter()
        val samples = FloatArray(48_000) { if (it % 2 == 0) 2.0f else -2.0f }
        limiter.process(samples)
        val max = samples.maxOf { kotlin.math.abs(it) }
        assertTrue("max=$max", max <= limiter.ceiling + 1e-3f)
    }

    @Test
    fun `no nan or denormal drift on silence`() {
        val limiter = Limiter()
        val samples = FloatArray(1_000) { 0f }
        limiter.process(samples)
        assertTrue(samples.all { it.isFinite() })
    }

    @Test
    fun `reset restores unity envelope`() {
        val limiter = Limiter()
        limiter.process(FloatArray(1_000) { 2f })
        limiter.reset()
        val out = limiter.process(FloatArray(1) { 0.05f })
        assertEquals(0.05f, out[0], 0.01f)
    }
}
