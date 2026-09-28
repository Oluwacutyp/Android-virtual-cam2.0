package com.vcamstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * r58 mandate: the swap pipeline's pure math, pinned on the JVM.
 * Landmines: TWO ranges (ArcFace (px-127.5)/127.5 vs inswapper px/255),
 * the embedding/latent norms must be 1.0, and emap is exactly
 * 1,048,576 bytes -> (512,512) floats.
 */
class SwapMathTest {

    @Test
    fun `arcface preprocess - pixel 0 to -1, pixel 255 to +1`() {
        val n = 3 * SwapMath.ARC_SIZE * SwapMath.ARC_SIZE
        val out = FloatArray(n)
        SwapMath.arcfacePreprocess(IntArray(SwapMath.ARC_SIZE * SwapMath.ARC_SIZE) { 0 }, out)
        assertEquals(-1.0f, out[0], 1e-6f)
        assertEquals(-1.0f, out[n / 3], 1e-6f) // green plane
        assertEquals(-1.0f, out[2 * n / 3], 1e-6f) // blue plane
        SwapMath.arcfacePreprocess(IntArray(SwapMath.ARC_SIZE * SwapMath.ARC_SIZE) { 0xFF or (0xFF shl 8) or (0xFF shl 16) }, out)
        assertEquals(+1.0f, out[0], 1e-6f)
        assertEquals(+1.0f, out[n / 3], 1e-6f)
        assertEquals(+1.0f, out[2 * n / 3], 1e-6f)
    }

    @Test
    fun `swap preprocess - pixel 0 to 0, pixel 255 to 1`() {
        val n = 3 * SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE
        val out = FloatArray(n)
        SwapMath.swapPreprocess(IntArray(SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE) { 0 }, out)
        assertEquals(0.0f, out[0], 1e-6f)
        assertEquals(0.0f, out[n / 3], 1e-6f)
        assertEquals(0.0f, out[2 * n / 3], 1e-6f)
        SwapMath.swapPreprocess(IntArray(SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE) { 0xFF or (0xFF shl 8) or (0xFF shl 16) }, out)
        assertEquals(1.0f, out[0], 1e-6f)
        assertEquals(1.0f, out[n / 3], 1e-6f)
        assertEquals(1.0f, out[2 * n / 3], 1e-6f)
    }

    @Test
    fun `l2 normalised embedding has norm 1`() {
        val v = FloatArray(SwapMath.EMBED_DIM) { ((it * 37) % 101) / 100f - 0.5f }
        val after = SwapMath.l2norm(v)
        assertEquals(1.0f, after, 1e-5f)
        var s = 0f
        for (x in v) s += x * x
        assertEquals(1.0f, sqrt(s), 1e-5f)
    }

    @Test
    fun `latent projection output l2 norm is 1 after normalisation`() {
        val latent = FloatArray(SwapMath.EMBED_DIM) { ((it * 53) % 97) / 97f - 0.5f }
        SwapMath.l2norm(latent)
        val emap = FloatArray(SwapMath.EMBED_DIM * SwapMath.EMBED_DIM) { ((it * 11) % 89) / 89f - 0.5f }
        val out = FloatArray(SwapMath.EMBED_DIM)
        SwapMath.project(latent, emap, out)
        val after = SwapMath.l2norm(out)
        assertEquals(1.0f, after, 1e-5f)
    }

    @Test
    fun `emap is exactly 1048576 bytes - 512x512 floats`() {
        assertTrue(SwapMath.validEmapBytes(1_048_576))
        assertEquals(512 * 512, 1_048_576 / 4)
        assertFalse(SwapMath.validEmapBytes(1_048_572))
        assertFalse(SwapMath.validEmapBytes(1_048_580))
        assertFalse(SwapMath.validEmapBytes(0))
    }
}
