package com.vcamstudio.app.ui.studio

import com.vcamstudio.engine.aiface.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r51-C1: the overlay box math — EMA per coordinate with no smoothing on a
 * null->box reappearance, and the front mirror applied exactly once.
 */
class FaceOverlayMathTest {

    private fun box(x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 0.9f) =
        FaceBox(x1, y1, x2, y2, score, timestampMs = 1234L, frameWidth = 720, frameHeight = 1280)

    @Test
    fun `first box after null passes through unchanged`() {
        val fresh = box(0.1f, 0.2f, 0.3f, 0.4f, score = 0.77f)
        assertEquals(fresh, FaceOverlayMath.smooth(null, fresh))
    }

    @Test
    fun `ema weights are 0_45 fresh plus 0_55 prev per coordinate`() {
        val prev = box(0.0f, 0.0f, 1.0f, 1.0f)
        val fresh = box(1.0f, 1.0f, 0.0f, 0.0f)
        val s = FaceOverlayMath.smooth(prev, fresh)
        assertEquals(0.45f, s.x1, 1e-6f)
        assertEquals(0.45f, s.y1, 1e-6f)
        assertEquals(0.55f, s.x2, 1e-6f)
        assertEquals(0.55f, s.y2, 1e-6f)
    }

    @Test
    fun `score timestamp and frame dims come from the fresh box`() {
        val prev = box(0.0f, 0.0f, 1.0f, 1.0f, score = 0.1f)
        val fresh = box(0.2f, 0.2f, 0.8f, 0.8f, score = 0.7f)
        val s = FaceOverlayMath.smooth(prev, fresh)
        assertEquals(0.7f, s.score, 1e-6f)
        assertEquals(1234L, s.timestampMs)
        assertEquals(720, s.frameWidth)
        assertEquals(1280, s.frameHeight)
    }

    @Test
    fun `mirror flips x exactly once and preserves y`() {
        val b = box(0.1f, 0.2f, 0.3f, 0.4f)
        val m = FaceOverlayMath.mirrorX(b)
        assertEquals(0.7f, m.x1, 1e-6f)
        assertEquals(0.9f, m.x2, 1e-6f)
        assertEquals(0.2f, m.y1, 1e-6f)
        assertEquals(0.4f, m.y2, 1e-6f)
    }

    @Test
    fun `double mirror is the identity within float tolerance`() {
        // 1f-(1f-x) is not bit-exact x (0.1f -> 0.100000024f); compare with delta.
        val b = box(0.1f, 0.2f, 0.3f, 0.4f, score = 0.5f)
        val d = FaceOverlayMath.mirrorX(FaceOverlayMath.mirrorX(b))
        assertEquals(b.x1, d.x1, 1e-5f)
        assertEquals(b.x2, d.x2, 1e-5f)
        assertEquals(b.y1, d.y1, 0f)
        assertEquals(b.y2, d.y2, 0f)
    }

    @Test
    fun `ema output stays within the convex hull of prev and fresh`() {
        val prev = box(0.0f, 0.0f, 0.5f, 0.5f)
        val fresh = box(0.9f, 0.9f, 1.0f, 1.0f)
        val s = FaceOverlayMath.smooth(prev, fresh)
        assertTrue(s.x1 in 0.0f..0.9f && s.y1 in 0.0f..0.9f)
        assertTrue(s.x2 in 0.5f..1.0f && s.y2 in 0.5f..1.0f)
    }
}
