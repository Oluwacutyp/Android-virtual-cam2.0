package com.vcamstudio.engine.render.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-28 golden harness (owner "ROUND 24 — ROTATION"): the ST-class
 * classifier and the pure compensation function, verified over the FULL
 * orientation space —
 *
 *     8 ST classes x {front, back} x sensor fold {0, 90, 180, 270} = 64 cases
 *
 * Every case must net to the desired orientation (back -> IDENTITY: upright,
 * not mirrored; front selfie -> MIRROR_H: upright, horizontally mirrored)
 * with EXACTLY ONE (uvRot, mirrorX) compensation, and [StOrientation.compensate]
 * must produce that unique hit. The net is measured by corner-probing the
 * EXACT production chain (SourceUvMath.transform: ST -> mirrorX -> rot CCW),
 * so this harness stays valid across devices and Android versions: it pins
 * the algebra, not a device.
 *
 * Canonical-ST construction here is written INDEPENDENTLY of the production
 * signature table (an intentional cross-check — a typo on either side fails).
 */
class StOrientationGoldenTest {

    // ---------------------------------------------------- canonical builders

    /** Independent canonical corner maps: (TL, du = TL->TR, dv = TL->BL). */
    private val CANONICAL: Map<Pair<Int, StMirror>, Triple<Pair<Int, Int>, Pair<Int, Int>, Pair<Int, Int>>> = mapOf(
        (0 to StMirror.NONE) to Triple((0 to 0), (1 to 0), (0 to 1)), // identity
        (0 to StMirror.H) to Triple((1 to 0), (-1 to 0), (0 to 1)), // u -> 1-u
        (0 to StMirror.V) to Triple((0 to 1), (1 to 0), (0 to -1)), // v -> 1-v (SurfaceTexture flipY)
        (90 to StMirror.NONE) to Triple((0 to 1), (0 to -1), (1 to 0)), // (u,v)->(v,1-u): content 90 CW
        (90 to StMirror.H) to Triple((1 to 1), (0 to -1), (-1 to 0)),
        (90 to StMirror.V) to Triple((0 to 0), (0 to 1), (1 to 0)),
        (180 to StMirror.NONE) to Triple((1 to 1), (-1 to 0), (0 to -1)),
        (270 to StMirror.NONE) to Triple((1 to 0), (0 to 1), (-1 to 0)), // (u,v)->(1-v,u)
    )

    /** Builds a column-major ST for a canonical class from its corner map. */
    private fun canonicalSt(rotCwDeg: Int, mirror: StMirror): FloatArray {
        val (tl, du, dv) = CANONICAL.getValue(rotCwDeg to mirror)
        return floatArrayOf(
            du.first.toFloat(), du.second.toFloat(), 0f, 0f,
            dv.first.toFloat(), dv.second.toFloat(), 0f, 0f,
            0f, 0f, 1f, 0f,
            tl.first.toFloat(), tl.second.toFloat(), 0f, 1f,
        )
    }

    /** Column-major 4x4 affine multiply: applies [b] first, then [a]. */
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        return out
    }

    /**
     * Sensor/display fold: extra CW content rotation folded into the
     * delivered ST (what a device whose HAL folds `sensor` degrees produces),
     * composed about the buffer center, still mapping the square onto itself.
     */
    private fun sensorFold(st: FloatArray, cwDeg: Int): FloatArray {
        val r = Math.toRadians(cwDeg.toDouble())
        val c = Math.cos(r).toFloat()
        val s = Math.sin(r).toFloat()
        // (u,v) -> center + Rccw * (p - center): inverse of a CW content rotation
        val rot = floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.5f - 0.5f * c + 0.5f * s, 0.5f - 0.5f * s - 0.5f * c, 0f, 1f,
        )
        return mul(rot, st)
    }

    // ------------------------------------------------------------- net probe

    /** Sampling window (TL, TR, BR, BL) in production corner order. */
    private val window = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /**
     * Classifies the COMPOSED net (ST -> mirrorX -> rot CCW) by probing the
     * transformed window axes — the same corner-probe idea as
     * SourceUvMath.classifyNet, reduced to the 6 canonical net labels.
     */
    private fun netClass(st: FloatArray, rotDeg: Float, mirrorX: Boolean): String {
        val c = SourceUvMath.transform(window, st, rotDeg, mirrorX, clamp = false)
        val dux = ((c[2] - c[0]) + (c[4] - c[6])) * 0.5f
        val duy = ((c[3] - c[1]) + (c[5] - c[7])) * 0.5f
        val dvx = ((c[6] - c[0]) + (c[4] - c[2])) * 0.5f
        val dvy = ((c[7] - c[1]) + (c[5] - c[3])) * 0.5f
        val nx = Math.round(dux / Math.hypot(dux.toDouble(), duy.toDouble()))
        val ny = Math.round(duy / Math.hypot(dux.toDouble(), duy.toDouble()))
        val mx = Math.round(dvx / Math.hypot(dvx.toDouble(), dvy.toDouble()))
        val my = Math.round(dvy / Math.hypot(dvx.toDouble(), dvy.toDouble()))
        return when {
            nx == 1 && ny == 0 && mx == 0 && my == 1 -> "IDENTITY"
            nx == -1 && ny == 0 && mx == 0 && my == 1 -> "MIRROR_H"
            nx == 1 && ny == 0 && mx == 0 && my == -1 -> "MIRROR_V"
            nx == -1 && ny == 0 && mx == 0 && my == -1 -> "ROT180"
            nx == 0 && ny == 1 && mx == -1 && my == 0 -> "ROT90CCW"
            nx == 0 && ny == -1 && mx == 1 && my == 0 -> "ROT90CW"
            else -> "OTHER"
        }
    }

    private fun desiredNet(frontFacing: Boolean): String = if (frontFacing) "MIRROR_H" else "IDENTITY"

    // ---------------------------------------------------------------- tests

    @Test
    fun `classifier roundtrips all 8 canonical classes`() {
        for ((key, _) in CANONICAL) {
            val st = canonicalSt(key.first, key.second)
            assertEquals("canonical $key roundtrip", StClass(key.first, key.second), StOrientation.classify(st))
        }
        assertEquals(8, CANONICAL.size)
    }

    @Test
    fun `device dump ST (st_hash 4AE91905) classifies to rot 90 mirror none`() {
        // From the round-27 device dump: ST=[0,-1,0,0, 1,0,0,0, 0,0,1,0, 0,1,0,1]
        val st = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        assertEquals(StClass(90, StMirror.NONE), StOrientation.classify(st))
    }

    @Test
    fun `common producer matrices classify`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertEquals(StClass(0, StMirror.NONE), StOrientation.classify(identity))

        // SurfaceTexture flipY: (u,v) -> (u, 1-v)
        val flipY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        assertEquals(StClass(0, StMirror.V), StOrientation.classify(flipY))

        // (u,v) -> (1-v, u) — the transposedFlip from SourceOrientationGoldenTest
        val transposedFlip = floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 0f,
        )
        assertEquals(StClass(270, StMirror.NONE), StOrientation.classify(transposedFlip))

        // Cropped/scaled ST (not a pure axis-aligned orientation) -> null;
        // RenderThread logs ST_CLASS_UNCLASSIFIED instead of a contract line.
        val cropped = floatArrayOf(
            0.5f, 0f, 0f, 0f,
            0f, -0.5f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.25f, 0.75f, 0f, 1f,
        )
        assertNull(StOrientation.classify(cropped))
    }

    @Test
    fun `device compensation anchors - class rot90 none front 270-true back 90-false`() {
        val cls = StClass(90, StMirror.NONE)
        val front = StOrientation.compensate(cls, frontFacing = true)
        assertEquals(270f, front.uvRotDeg, 0.01f)
        assertTrue(front.mirrorX)
        val back = StOrientation.compensate(cls, frontFacing = false)
        assertEquals(90f, back.uvRotDeg, 0.01f)
        assertTrue(!back.mirrorX)
        // And the device ST through the full chain nets exactly the desired.
        val st = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        assertEquals("MIRROR_H", netClass(st, front.uvRotDeg, front.mirrorX))
        assertEquals("IDENTITY", netClass(st, back.uvRotDeg, back.mirrorX))
    }

    @Test
    fun `golden 64 - every ST class x facing x sensor fold nets to the desired orientation`() {
        val sensors = intArrayOf(0, 90, 180, 270)
        var cases = 0
        for (base in CANONICAL.keys) {
            for (sensor in sensors) {
                val st = sensorFold(canonicalSt(base.first, base.second), sensor)
                val classified = StOrientation.classify(st)
                assertNotNull("folded ST $base sensor=$sensor must classify, got OTHER", classified)
                for (front in booleanArrayOf(false, true)) {
                    val desired = desiredNet(front)
                    val comp = StOrientation.compensate(classified!!, front)
                    // Exactly ONE (rot, mirrorX) in the compensation vocabulary
                    // must reach the desired net, and compensate() picks it.
                    val hits = ArrayList<Pair<Int, Boolean>>()
                    for (rot in intArrayOf(0, 90, 180, 270)) {
                        for (mx in booleanArrayOf(false, true)) {
                            if (netClass(st, rot.toFloat(), mx) == desired) hits.add(rot to mx)
                        }
                    }
                    assertEquals(
                        "case base=$base sensor=$sensor front=$front: exactly one compensating (rot,mx)",
                        1, hits.size,
                    )
                    assertEquals(
                        "case base=$base sensor=$sensor front=$front: compensate rot == unique hit",
                        hits[0].first.toFloat(), comp.uvRotDeg, 0.01f,
                    )
                    assertEquals(
                        "case base=$base sensor=$sensor front=$front: compensate mirrorX == unique hit",
                        hits[0].second, comp.mirrorX,
                    )
                    cases++
                }
            }
        }
        assertEquals(64, cases)
    }
}
