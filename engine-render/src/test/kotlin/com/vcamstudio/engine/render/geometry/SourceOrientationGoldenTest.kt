package com.vcamstudio.engine.render.geometry

import com.vcamstudio.engine.render.model.FitMode
import com.vcamstudio.engine.render.model.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Golden-frame tests for the source-orientation pipeline (the class of bug
 * that shipped sideways/upside-down camera frames and the letterbox bars —
 * rounds 9-14). This is a SOFTWARE RASTERIZER over the exact production math:
 *
 *   LayerGeometry.compute (fit/crop)  ->  SourceUvMath (ST -> rotate -> mirror)
 *
 * and asserts the DISPLAYED orientation of an asymmetric "F" glyph, measured
 * mechanically from the affine screen->image map (no hand-waved algebra).
 *
 * Device-calibrated contract (rounds 12-14, both test phones, sensor 270):
 *   - sampling rot 0   -> content 90 deg off   (r9/r12 build A)
 *   - sampling rot 90  -> content upside-down  (r10-r12 build B, geometry-stage
 *     270 pre-ST == renderer-stage 90 post-ST)
 *   - sampling rot 270 -> UPRIGHT              (the shipped value)
 * i.e. the buffer content sits at delta=270 CW and displayed = delta - rot.
 */
class SourceOrientationGoldenTest {

    // ---------------------------------------------------------------- model

    /** Screen axes -> image-space direction, via the affine quad map. */
    private data class Orientation(
        val rotationCw: Int,   // 0 = upright, 90/180/270
        val mirrored: Boolean, // horizontal mirror of the displayed image
        val outOfBounds: Boolean, // any corner UV outside [0,1] (wedge risk)
    )

    /**
     * Rasterizes the layer pipeline for a camera-like layer (FILL, 100%,
     * centered) with the given producer ST matrix, sampling rotation and
     * mirror, and measures the displayed orientation of content whose buffer
     * sits rotated [deltaCw] from upright.
     */
    private fun measure(
        srcW: Float,
        srcH: Float,
        outW: Float,
        outH: Float,
        st: FloatArray?,
        rotDeg: Float,
        mirrorX: Boolean,
        deltaCw: Int,
    ): Orientation {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FILL),
            sourceWidthPx = srcW,
            sourceHeightPx = srcH,
            sceneWidthPx = outW,
            sceneHeightPx = outH,
        )
        val finalUvs = SourceUvMath.transform(quad.uvs, st, rotDeg, mirrorX, clamp = false)
        val corners = (0 until 4).map { i ->
            // Screen maps to (u,v) with v in SAMPLING convention (v down the
            // buffer visually after the flipY ST). Screen TL -> finalUvs[0..1].
            Pair(finalUvs[i * 2].toDouble(), finalUvs[i * 2 + 1].toDouble())
        } // TL, TR, BR, BL
        val oob = corners.any { (u, v) -> u < -EPS || u > 1 + EPS || v < -EPS || v > 1 + EPS }

        // Affine screen(u right, v DOWN in sampling space) -> buffer space.
        // Buffer content sits at delta CW from upright; undo it to get the
        // image-space point each screen position displays.
        val rad = Math.toRadians(-deltaCw.toDouble())
        fun toImage(fx: Double, fy: Double): Pair<Double, Double> {
            val ut = corners[0].first + (corners[1].first - corners[0].first) * fx
            val ub = corners[3].first + (corners[2].first - corners[3].first) * fx
            val vt = corners[0].second + (corners[1].second - corners[0].second) * fx
            val vb = corners[3].second + (corners[2].second - corners[3].second) * fx
            val u = ut + (ub - ut) * fy
            val v = vt + (vb - vt) * fy
            val ru = u - 0.5
            val rv = v - 0.5
            return Pair(
                0.5 + ru * kotlin.math.cos(rad) - rv * kotlin.math.sin(rad),
                0.5 + ru * kotlin.math.sin(rad) + rv * kotlin.math.cos(rad),
            )
        }
        // Screen-right and screen-down expressed in image space (image y UP).
        val (rx1, ry1) = toImage(0.75, 0.5)
        val (rx0, ry0) = toImage(0.25, 0.5)
        val (dx1, dy1) = toImage(0.5, 0.75)
        val (dx0, dy0) = toImage(0.5, 0.25)
        val angRight = Math.toDegrees(atan2(ry1 - ry0, rx1 - rx0))
        val angDown = Math.toDegrees(atan2(dy1 - dy0, dx1 - dx0))
        val mirrored = (angRight / 90.0).roundToInt() % 4 == 2 // points image-left
        val rotationCw = (((angDown / 90.0).roundToInt() - 3) % 4 + 4) % 4 * 90 // screen-down vs image-up(=270 deg)
        return Orientation(rotationCw, mirrored, oob)
    }

    /** SurfaceTexture flipY producer matrix (column-major). */
    private fun flipY(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 0f,
    )

    /** (u,v) -> (1-v, u): 90-deg producer rotation composed with a flip, mapped back onto the unit square. */
    private fun transposedFlip(): FloatArray = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 0f, 0f, 0f,
    )

    // ------------------------------------------------- camera golden frames

    @Test
    fun `back camera sensor 270 is upright at sampling rotation 270`() {
        val o = measure(640f, 480f, 720f, 1280f, flipY(), rotDeg = 270f, mirrorX = false, deltaCw = 270)
        assertEquals(0, o.rotationCw)
        assertEquals(false, o.mirrored)
        assertEquals(false, o.outOfBounds)
    }

    @Test
    fun `sampling rotation 0 shows the r9-r12 ninety-degree defect`() {
        val o = measure(640f, 480f, 720f, 1280f, flipY(), rotDeg = 0f, mirrorX = false, deltaCw = 270)
        assertEquals(90, o.rotationCw)
    }

    @Test
    fun `sampling rotation 90 shows the r10-r12 upside-down defect`() {
        // Memorializes the shipped-wrong value (geometry-stage 270 == renderer 90).
        val o = measure(640f, 480f, 720f, 1280f, flipY(), rotDeg = 90f, mirrorX = false, deltaCw = 270)
        assertEquals(180, o.rotationCw)
    }

    @Test
    fun `front camera is upright AND horizontally mirrored at rotation 270`() {
        val o = measure(640f, 480f, 720f, 1280f, flipY(), rotDeg = 270f, mirrorX = true, deltaCw = 270)
        assertEquals(0, o.rotationCw)
        assertEquals(true, o.mirrored)
        assertEquals(false, o.outOfBounds)
    }

    @Test
    fun `mirror stays a horizontal display mirror under 90-degree sampling rotation`() {
        // The pre-ST geometry mirror conjugated into a VERTICAL flip through the
        // ST matrix (the "front camera not consistently mirrored" bug).
        val upright = measure(640f, 480f, 720f, 1280f, flipY(), 270f, false, 270)
        val mirrored = measure(640f, 480f, 720f, 1280f, flipY(), 270f, true, 270)
        assertEquals(upright.rotationCw, mirrored.rotationCw)
        assertEquals(!upright.mirrored, mirrored.mirrored)
    }

    // -------------------------------------------------- video golden frames

    @Test
    fun `video with metadata rotation 90 is upright at sampling rotation 90`() {
        // 16:9 phone video, unappliedRotationDegrees = 90; ExoPlayer contract:
        // sampling rotation == unappliedRotationDegrees.
        val o = measure(1920f, 1080f, 720f, 1280f, flipY(), rotDeg = 90f, mirrorX = false, deltaCw = 90)
        assertEquals(0, o.rotationCw)
        assertEquals(false, o.mirrored)
        assertEquals(false, o.outOfBounds)
    }

    // ------------------------------------------------- wedge (OOB) invariants

    @Test
    fun `sampling stays in bounds across rotations and producer matrices`() {
        val sts = listOf(null, flipY(), transposedFlip())
        for (st in sts) {
            for (rot in intArrayOf(0, 90, 180, 270)) {
                val o = measure(640f, 480f, 720f, 1280f, st, rot.toFloat(), mirrorX = (rot == 270), deltaCw = 270)
                assertTrue(
                    "OOB UVs with st=${st != null} rot=$rot (the diagonal-wedge class)",
                    !o.outOfBounds,
                )
            }
        }
    }

    @Test
    fun `clamped output never leaves the unit square`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FILL),
            640f, 480f, 720f, 1280f,
        )
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val out = SourceUvMath.transform(quad.uvs, flipY(), rot.toFloat(), mirrorX = true, clamp = true)
            assertTrue(SourceUvMath.inBounds(out))
        }
    }

    // ---------------------------------------------------- geometry contracts

    @Test
    fun `fill quad corners cover the whole output with a symmetric uv window`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FILL),
            720f, 1280f, 1028f, 1675f,
        )
        // corners == output box exactly (FILL crops UVs, never the geometry)
        assertEquals(0f, quad.cornersPx[0], 1e-3f)
        assertEquals(0f, quad.cornersPx[1], 1e-3f)
        assertEquals(1028f, quad.cornersPx[4], 1e-3f)
        assertEquals(1675f, quad.cornersPx[5], 1e-3f)
        // window symmetric about 0.5 on both axes
        assertEquals(0.5f, (quad.uvs[0] + quad.uvs[2]) / 2f, 1e-4f)
        assertEquals(0.5f, (quad.uvs[1] + quad.uvs[5]) / 2f, 1e-4f)
    }

    @Test
    fun `geometry is mirror-agnostic - mirrors live in the renderer post-ST`() {
        val plain = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.STRETCH),
            100f, 100f, 1000f, 1000f,
        )
        val mirrored = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.STRETCH, mirrorX = true),
            100f, 100f, 1000f, 1000f,
        )
        assertTrue(plain.uvs.contentEquals(mirrored.uvs))
        // renderer-side mirror flips about the window center:
        val out = SourceUvMath.transform(plain.uvs, null, 0f, mirrorX = true)
        assertEquals(plain.uvs[2], out[0], 1e-5f) // TL u becomes TR u
        assertEquals(plain.uvs[0], out[2], 1e-5f)
    }

    companion object {
        private const val EPS = 1e-4 // 90-degree fp rotations fuzz the boundary by ~1 ulp
    }
}
