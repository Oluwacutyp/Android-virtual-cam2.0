package com.vcamstudio.engine.render.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-28 golden harness (owner "STOP GUESSING CONSTANTS. RESTRUCTURE."):
 * tests the EXACT composition order of the SOLE derivation function
 * [StOrientation.transformFromST] — ST matrix in, (uvRot, mirrorX) out —
 * over the full orientation + display space:
 *
 *     8 ST classes x {isFront} x sensor fold {0, 90, 180, 270}
 *     x display {0, 90, 180, 270} = 256 cases
 *
 * Each case: fold a canonical ST by the sensor angle, feed the FOLDED
 * MATRIX through transformFromST (the real classify-inside path), compose
 * the resulting layer chain Rot(uvRot) . Mirror(mirrorX) . ST as 2x2
 * matrices, and assert the closed-form laws of the derivation:
 *
 *     RIGID    -> the net is one of EXACTLY the 8 D4 transforms (never a
 *                 shear — the runtime decoder names all 8 as of round 27);
 *     improper -> IFF isFront (back/video clean chains are proper);
 *     clean    -> net == R((270 - display) mod 360) for EVERY ST class —
 *                 the device display-frame law (displayed(X) = R180·X,
 *                 upright-clean = canonical R90; derived from the owner's
 *                 r24/r25/r26 calibration reports, see StOrientation.kt).
 *
 * Anchors pin the device-calibrated values at display 0, class rot=90/none:
 * front (isFront) -> uvRot=0/mirrorX=true (net MH_R270); back ->
 * uvRot=0/mirrorX=false (net R270); video (rot=90/h, same function) ->
 * uvRot=0/mirrorX=true (net R270). The r24 (270/true), r25 (90/true) and
 * r26 (180/true) front values are all superseded and must not reappear.
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

    // --------------------------------------------------- 2x2 matrix algebra

    /** Row-major 2x2 (linear parts; translations do not affect orientation). */
    private fun mmul(a: FloatArray, b: FloatArray): FloatArray {
        val (p, q, r, s) = a
        val (e, f, g, h) = b
        return floatArrayOf(p * e + q * g, p * f + q * h, r * e + s * g, r * f + s * h)
    }

    private val I = floatArrayOf(1f, 0f, 0f, 1f)
    private val R90 = floatArrayOf(0f, -1f, 1f, 0f) // engine Rot90 linear
    private val R180 = floatArrayOf(-1f, 0f, 0f, -1f)
    private val R270 = floatArrayOf(0f, 1f, -1f, 0f) // engine Rot270 linear
    private val MH = floatArrayOf(-1f, 0f, 0f, 1f) // mirrorX linear
    private val MV = floatArrayOf(1f, 0f, 0f, -1f)

    private fun mrot(deg: Int): FloatArray = when (((deg % 360) + 360) % 360) {
        0 -> I; 90 -> R90; 180 -> R180; else -> R270
    }

    /** Linear part of a canonical class ST (from its corner map). */
    private fun classLin(rotCwDeg: Int, mirror: StMirror): FloatArray {
        val (_, du, dv) = CANONICAL.getValue(rotCwDeg to mirror)
        return floatArrayOf(du.first.toFloat(), dv.first.toFloat(), du.second.toFloat(), dv.second.toFloat())
    }

    /**
     * Sensor/display fold as a FULL 4x4 column-major affine (square-to-square
     * about the center): (u,v) -> (c*u + s*v + t1, -s*u + c*v + t2) with
     * t1 = 0.5 - 0.5(c+s), t2 = 0.5 + 0.5s - 0.5c.
     */
    private fun foldLin(cwDeg: Int): FloatArray {
        val rad = Math.toRadians(cwDeg.toDouble())
        val c = Math.cos(rad).toFloat()
        val s = Math.sin(rad).toFloat()
        return floatArrayOf(
            c, -s, 0f, 0f,
            s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.5f - 0.5f * c - 0.5f * s, 0.5f + 0.5f * s - 0.5f * c, 0f, 1f,
        )
    }

    /**
     * Zero-sign/epsilon-insensitive float equality. FloatArray.contentEquals
     * is Arrays.equals(float[]), i.e. Float.equals semantics: -0.0f != 0.0f.
     * mmul products routinely produce -0f (e.g. 0f*(-1f) + (-1f)*0f), which
     * made every mirrored net compare unequal to its D4 constant ("OTHER").
     */
    private fun feq(a: FloatArray, b: FloatArray): Boolean =
        a.size == b.size && a.indices.all { Math.abs(a[it] - b[it]) < 1e-4f }

    /** D4 label of a matrix, or null if not axis-aligned (must never happen here). */
    private fun d4Label(m: FloatArray): String = when {
        feq(m, I) -> "R0"
        feq(m, R90) -> "R90"
        feq(m, R180) -> "R180"
        feq(m, R270) -> "R270"
        feq(m, MH) -> "MH"
        feq(m, MV) -> "MV"
        feq(m, mmul(MH, R90)) -> "MH_R90"
        feq(m, mmul(MH, R270)) -> "MH_R270"
        else -> "OTHER"
    }

    private val RIGID8: List<FloatArray> by lazy {
        listOf(I, R90, R180, R270, MH, MV, mmul(MH, R90), mmul(MH, R270))
    }

    /**
     * Round-27 step 2: the composition {ST_class} + {comp} must be one of
     * EXACTLY the 8 rigid transforms of the square
     * {R0,R90,R180,R270} x {no-mirror, mirror} (the mirror family includes
     * the two diagonal reflections). Fails loudly on anything else — a shear
     * or degenerate map must never reach a device dump unnoticed.
     */
    private fun assertRigid(net: FloatArray, case: String) {
        assertTrue(
            "$case: net not one of the 8 rigid transforms: ${net.toList()}",
            RIGID8.any { feq(net, it) },
        )
    }

    /**
     * Frame-free geometric laws the round-28 derivation MUST satisfy, per
     * case, on the composed net (canonical frame):
     *   RIGID    -> member of the exact 8 (assertRigid);
     *   improper -> IFF isFront (mirrored selfie net; back/video proper);
     *   clean    -> EXACTLY R((270 - display) mod 360), class-independent.
     */
    private fun assertMandateNet(
        net: FloatArray,
        isFront: Boolean,
        display: Int,
        case: String,
    ) {
        assertRigid(net, case)
        val label = d4Label(net)
        val improper = label.startsWith("MH") || label == "MV"
        assertEquals("$case: mirrored state must equal isFront", isFront, improper)
        if (!isFront) {
            assertEquals(
                "$case: clean net must be exactly R((270-display) mod 360)",
                "R${(270 - display % 360 + 360) % 360}",
                label,
            )
        }
    }

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
        // RenderThread treats it as identity (rot=0/mirror=none) and logs
        // ST_CLASS_UNCLASSIFIED — never skips.
        val cropped = floatArrayOf(
            0.5f, 0f, 0f, 0f,
            0f, -0.5f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.25f, 0.75f, 0f, 1f,
        )
        assertNull(StOrientation.classify(cropped))
    }

    @Test
    fun `round-28 device anchors - class rot90 none display 0`() {
        // THE round-28 derivation at display 0: uvRot = rotCw - 0 - 90 (mod 360).
        // front selfie: upright + mirrored on device -> uvRot=0/mirrorX=true
        // (supersedes r24 270/true, r25 90/true, r26 180/true).
        val frontSt = canonicalSt(90, StMirror.NONE)
        val front = StOrientation.transformFromST(frontSt, isFront = true, displayRotation = 0)!!
        assertEquals(0f, front.uvRotDeg, 0.01f)
        assertTrue(front.mirrorX)
        // back camera: same derivation, isFront=false -> uvRot=0/mirrorX=false.
        val back = StOrientation.transformFromST(frontSt, isFront = false, displayRotation = 0)!!
        assertEquals(0f, back.uvRotDeg, 0.01f)
        assertTrue(!back.mirrorX)
        // Video (device dump: class rot=90 mirror=h) — SAME function, no own
        // formula: uvRot=0/mirrorX=true at display 0.
        val video = StOrientation.transformFromST(canonicalSt(90, StMirror.H), isFront = false, displayRotation = 0)!!
        assertEquals(0f, video.uvRotDeg, 0.01f)
        assertTrue(video.mirrorX)

        // Net pins (canonical frame, device display law displayed(X)=R180.X):
        // front net = MH_R270 (displays upright+mirrored: R180.MH_R270 = MH_R90
        // = the device's upright-mirror state); back/video nets = R270
        // (displays upright: R180.R270 = R90 = the device upright-clean state).
        val frontNet = mmul(mrot(front.uvRotDeg.toInt()), mmul(if (front.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        assertRigid(frontNet, "front net (round-28)")
        assertEquals("MH_R270", d4Label(frontNet))
        val backNet = mmul(mrot(back.uvRotDeg.toInt()), mmul(if (back.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        assertRigid(backNet, "back net (round-28)")
        assertEquals("R270", d4Label(backNet))
        val videoNet = mmul(mrot(video.uvRotDeg.toInt()), mmul(if (video.mirrorX) MH else I, classLin(90, StMirror.H)))
        assertRigid(videoNet, "video net (round-28)")
        assertEquals("R270", d4Label(videoNet))

        // The device dump ST (st_hash 4AE91905) still classifies rot=90/none
        // through the mandated signature.
        val devSt = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        val fromDevice = StOrientation.transformFromST(devSt, isFront = true, displayRotation = 0)!!
        assertEquals(front, fromDevice)
    }

    @Test
    fun `superseded front values must not reappear at display 0`() {
        // r24=270/true, r25=90/true, r26=180/true — all read wrong on device.
        val front = StOrientation.transformFromST(canonicalSt(90, StMirror.NONE), true, 0)!!
        assertTrue("r24 regression", front.uvRotDeg != 270f)
        assertTrue("r25 regression", front.uvRotDeg != 90f)
        assertTrue("r26 regression", front.uvRotDeg != 180f)
    }

    @Test
    fun `golden 256 - every class x desired x sensor x display nets the mandated orientation`() {
        val sensors = intArrayOf(0, 90, 180, 270)
        val displays = intArrayOf(0, 90, 180, 270)
        var cases = 0
        for ((key, _) in CANONICAL) {
            for (sensor in sensors) {
                // Round-25 display fold: re-classify the folded ST (production path).
                val folded = mulSt(foldLin(sensor), canonicalSt(key.first, key.second))
                val cls = StOrientation.classify(folded)
                assertNotNull("folded ST $key sensor=$sensor must classify", cls)
                for (isFront in booleanArrayOf(false, true)) {
                    for (display in displays) {
                        // The FULL mandated path: folded MATRIX in, transform out.
                        val comp = StOrientation.transformFromST(folded, isFront, display)!!
                        // Compose the exact engine chain net: Rot . Mirror . ST.
                        val net = mmul(
                            mrot(comp.uvRotDeg.toInt()),
                            mmul(if (comp.mirrorX) MH else I, classLin(cls.rotCwDeg, cls.mirror)),
                        )
                        assertMandateNet(
                            net, isFront, display,
                            "base=$key sensor=$sensor isFront=$isFront display=$display " +
                                "comp=(${comp.uvRotDeg},${comp.mirrorX})",
                        )
                        cases++
                    }
                }
            }
        }
        assertEquals(256, cases)
    }

    /** Column-major 4x4 affine multiply (apply b first, then a). */
    private fun mulSt(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        return out
    }
}
