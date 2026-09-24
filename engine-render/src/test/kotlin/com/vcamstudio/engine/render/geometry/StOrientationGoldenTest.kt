package com.vcamstudio.engine.render.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-25 golden harness (owner "ROUND 25 — ROTATION. SIGN FLIP + VIDEO
 * ROUTING"): the ST-class classifier and the pure compensation function,
 * verified over the FULL orientation + display space —
 *
 *     8 ST classes x {mirror, clean} x sensor fold {0, 90, 180, 270}
 *     x display {0, 90, 180, 270} = 256 cases
 *
 * Each case: fold a canonical ST by the sensor angle, classify it with the
 * REAL production classifier, compensate with the REAL production function
 * (round-25 sign: uvRot = rotCw - display, V-shift for V classes, XOR
 * mirror), then compose the resulting NET through the exact engine chain
 * (Rot(uvRot) . Mirror(mirrorX) . ST) as 2x2 matrices and assert it against
 * an INDEPENDENT closed form:
 *
 *     clean desired  -> net == R(360 - display)  (canonical upright, exactly)
 *     mirror desired -> net improper AND the mandated mirror/rot form
 *
 * Canonical-identity displays upright in ANY frame convention, so the clean
 * assertion pins "upright in the display frame" up to the single calibrated
 * device fact (round-25 dump: canonical nets read through the compositor
 * with an R90-conjugated frame). Anchors pin the device-calibrated values:
 * front class rot=90/none at display 0 -> uvRot=90/mirrorX=true (the r28
 * 270/true value is the flipped one and MUST NOT reappear).
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

    /**
     * Frame-free geometric laws the mandate's algebra MUST satisfy, asserted
     * per case on the composed net (canonical frame):
     *   clean desired  -> net == R(360 - display) EXACTLY (canonical upright
     *                     carrying the display compensation — identity at
     *                     display 0, upright in ANY frame convention);
     *   mirror desired -> net improper (a displayed mirror state), and the
     *                     anchor test pins the axis/rotation on the device
     *                     dump ST (front -> MH: upright + mirrored).
     */
    private fun assertMandateNet(
        net: FloatArray,
        desiredMirrorH: Boolean,
        display: Int,
        case: String,
    ) {
        val label = d4Label(net)
        assertTrue("$case: net not axis-aligned: $label", label != "OTHER")
        val improper = label.startsWith("MH") || label == "MV"
        assertEquals("$case: displayed-mirror state", desiredMirrorH, improper)
        if (!desiredMirrorH) {
            assertEquals("$case: clean net must be exactly R(360-display)", "R${(360 - display) % 360}", label)
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
    fun `round-25 device anchors - class rot90 none display 0`() {
        // Front selfie: upright + mirrored. THE r25 flip: uvRot=90 (r28 ran 270).
        val front = StOrientation.compensate(StClass(90, StMirror.NONE), desiredMirrorH = true, displayRotDeg = 0)
        assertEquals(90f, front.uvRotDeg, 0.01f)
        assertTrue(front.mirrorX)
        // Back camera: canonical-identity net (upright in any display frame).
        val back = StOrientation.compensate(StClass(90, StMirror.NONE), desiredMirrorH = false, displayRotDeg = 0)
        assertEquals(90f, back.uvRotDeg, 0.01f)
        assertTrue(!back.mirrorX)
        val devSt = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        val frontNet = mmul(mrot(front.uvRotDeg.toInt()), mmul(if (front.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        // Canonical net for the mandated front comp: MV. The device display
        // frame reads canonical MV as upright+mirrored (round-27 screenshot
        // calibration: r28's canonical-MH front displayed upside-down, i.e.
        // the device frame conjugates by R90 — the mandate's 90/true target
        // is exactly the MV net).
        assertEquals("MV", d4Label(frontNet))
        assertEquals(StClass(90, StMirror.NONE), StOrientation.classify(devSt))

        // Back net through the chain MUST be canonical identity (R0).
        val backNet = mmul(mrot(back.uvRotDeg.toInt()), mmul(if (back.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        assertEquals("R0", d4Label(backNet))

        // Video (dump: class rot=90 mirror=h), clean desired -> identity net.
        val video = StOrientation.compensate(StClass(90, StMirror.H), desiredMirrorH = false, displayRotDeg = 0)
        assertEquals(90f, video.uvRotDeg, 0.01f)
        assertTrue(video.mirrorX)
        val videoNet = mmul(mrot(video.uvRotDeg.toInt()), mmul(if (video.mirrorX) MH else I, classLin(90, StMirror.H)))
        assertEquals("R0", d4Label(videoNet))
    }

    @Test
    fun `r28 sign must not reappear at display 0`() {
        // The r28 front value for this class was uvRot=270/mirrorX=true.
        val front = StOrientation.compensate(StClass(90, StMirror.NONE), true, 0)
        assertTrue("r28 flip regression", front.uvRotDeg != 270f)
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
                for (desiredMirrorH in booleanArrayOf(false, true)) {
                    for (display in displays) {
                        val comp = StOrientation.compensate(cls!!, desiredMirrorH, display)
                        // Compose the exact engine chain net: Rot . Mirror . ST.
                        val net = mmul(
                            mrot(comp.uvRotDeg.toInt()),
                            mmul(if (comp.mirrorX) MH else I, classLin(cls.rotCwDeg, cls.mirror)),
                        )
                        assertMandateNet(
                            net, desiredMirrorH, display,
                            "base=$key sensor=$sensor dm=$desiredMirrorH display=$display " +
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
