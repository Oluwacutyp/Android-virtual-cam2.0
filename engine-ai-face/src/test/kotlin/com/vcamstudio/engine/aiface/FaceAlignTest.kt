package com.vcamstudio.engine.aiface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * r52a: the similarity alignment math for the swap chain. Property under
 * test: landmarks placed exactly on a template warp onto that template
 * within 0.5 px (both 112 and 128), and a known synthetic similarity is
 * recovered exactly. Same synthetic/no-device discipline as the decode
 * tests.
 */
class FaceAlignTest {

    private fun feq(a: Float, b: Float, eps: Float = 0.5f) = abs(a - b) < eps

    @Test
    fun `template rule - 112 is raw arcface_dst`() {
        val t = FaceAlign.template(112)
        for (i in 0 until 10) assertEquals(FaceAlign.ARCFACE_DST[i], t[i], 1e-4f)
    }

    @Test
    fun `template rule - 128 shifts x by 8 and does not scale by 128-112`() {
        val t = FaceAlign.template(128)
        assertEquals(FaceAlign.ARCFACE_DST[0] + 8f, t[0], 1e-4f)
        assertEquals(FaceAlign.ARCFACE_DST[1], t[1], 1e-4f)
        // NOT x * 128/112 (that would be 43.76 for the first eye):
        assertTrue(abs(t[0] - FaceAlign.ARCFACE_DST[0] * 128f / 112f) > 1f)
    }

    @Test
    fun `template rule - 224 scales by 2 with no diff, 256 scales by 2 with diff 16`() {
        val t224 = FaceAlign.template(224)
        assertEquals(FaceAlign.ARCFACE_DST[0] * 2f, t224[0], 1e-3f)
        val t256 = FaceAlign.template(256)
        assertEquals(FaceAlign.ARCFACE_DST[0] * 2f + 16f, t256[0], 1e-3f)
    }

    private fun assertWarpIdentity(imageSize: Int) {
        val tmpl = FaceAlign.template(imageSize)
        val m = FaceAlign.estimate(tmpl, imageSize)
        assertNotNull(m)
        val warped = m!!.map(tmpl)
        for (i in 0 until 10) {
            assertEquals(tmpl[i], warped[i], 0.5f)
        }
    }

    @Test
    fun `identity warp - landmarks on template land on template (112)`() = assertWarpIdentity(112)

    @Test
    fun `identity warp - landmarks on template land on template (128)`() = assertWarpIdentity(128)

    @Test
    fun `known synthetic similarity is recovered and maps onto the 112 template`() {
        // Apply scale 1.8, rotation 17deg, translation (31, -12) to the
        // template -> synthetic detection landmarks; estimate must invert it.
        val theta = Math.toRadians(17.0).toFloat()
        val c = cos(theta); val s = sin(theta)
        val tmpl = FaceAlign.template(112)
        val src = FloatArray(10)
        for (i in 0 until 5) {
            val x = tmpl[2 * i]; val y = tmpl[2 * i + 1]
            src[2 * i] = 1.8f * (c * x - s * y) + 31f
            src[2 * i + 1] = 1.8f * (s * x + c * y) - 12f
        }
        val m = FaceAlign.estimate(src, 112)!!
        val warped = m.map(src)
        for (i in 0 until 5) {
            assertEquals(tmpl[2 * i], warped[2 * i], 0.5f)
            assertEquals(tmpl[2 * i + 1], warped[2 * i + 1], 0.5f)
        }
        // The recovered linear part is the exact inverse similarity
        // (theta = -17deg: cos is even, sin is not).
        val invScale = 1f / 1.8f
        assertEquals(invScale * c, m.m[0], 1e-3f)
        assertEquals(invScale * s, -m.m[3], 1e-3f)
    }

    @Test
    fun `inverse of the estimate maps template back onto the source`() {
        val theta = Math.toRadians(-30.0).toFloat()
        val c = cos(theta); val s = sin(theta)
        val tmpl = FaceAlign.template(128)
        val src = FloatArray(10)
        for (i in 0 until 5) {
            val x = tmpl[2 * i]; val y = tmpl[2 * i + 1]
            src[2 * i] = 1.3f * (c * x - s * y) + 5f
            src[2 * i + 1] = 1.3f * (s * x + c * y) + 7f
        }
        val m = FaceAlign.estimate(src, 128)!!
        val inv = m.inverse()!!
        val back = inv.map(tmpl)
        for (i in 0 until 5) {
            assertEquals(src[2 * i], back[2 * i], 0.01f)
            assertEquals(src[2 * i + 1], back[2 * i + 1], 0.01f)
        }
    }

    @Test
    fun `degenerate point set returns null`() {
        val src = FloatArray(10) // all zeros
        assertNull(FaceAlign.estimate(src, 112))
    }
}
