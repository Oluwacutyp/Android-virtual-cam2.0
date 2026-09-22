package com.vcamstudio.engine.audio

import kotlin.math.exp
import kotlin.math.max

/**
 * Feedback-style stereo peak limiter — allocation-free and deterministic
 * (unit-tested). Below threshold: unity gain. Peaks above threshold pull the
 * envelope toward threshold/peak with fast attack smoothing, then release
 * back to unity. Output is hard-capped at [ceiling].
 */
class Limiter(
    var threshold: Float = 0.891f, // -1 dBFS
    var ceiling: Float = 0.985f,   // ≈ -0.13 dBFS
    attackMs: Float = 1.5f,
    releaseMs: Float = 80f,
    sampleRate: Int = 48_000,
) {
    private var envelope = 1f
    private val attackCoef = exp(-1.0 / (attackMs * 0.001 * sampleRate))
    private val releaseCoef = exp(-1.0 / (releaseMs * 0.001 * sampleRate))

    /** Processes interleaved samples in place; returns the same array. */
    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            val input = samples[i]
            val peak = max(input, -input)
            val target = if (peak > threshold) threshold / peak else 1f
            val coef = if (target < envelope) attackCoef else releaseCoef
            envelope = (target + (envelope - target) * coef).toFloat()
            var out = input * envelope
            if (out > ceiling) {
                out = ceiling
            } else if (out < -ceiling) {
                out = -ceiling
            }
            samples[i] = out
        }
        return samples
    }

    fun reset() {
        envelope = 1f
    }
}
