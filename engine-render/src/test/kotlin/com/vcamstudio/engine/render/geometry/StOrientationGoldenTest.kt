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
 *     clean desired  -> net == R(360 - display)   (rot != 90 classes)
 *                       net == R(450 - display)   (rot=90 family: the r26
 *                       device-calibrated bias — canonical nets read 90-off
 *                       on the device, mirrored chains in the opposite
 *                       direction from clean ones)
 *     mirror desired -> net improper AND the mandated mirror/rot form
 *
 * Anchors pin the device-calibrated values (r26 owner ladder: r24 uvRot=270
 * -> 180 off; r25 uvRot=90 -> 90 off; r26 uvRot=180 = the remaining
 * constant): front class rot=90/none at display 0 -> uvRot=180/mirrorX=true,
 * back same class -> uvRot=180/mirrorX=false, video (rot=90/h, same
 * function) -> uvRot=180/mirrorX=true. The r28 270/true value MUST NOT
 * reappear; the r25 90/90 pair read 90-off in opposite directions.
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
     * Frame-free geometric laws the mandate's algebra MUST satisfy, asserted
     * per case on the composed net (canonical frame):
     *   RIGID           -> the net is one of the 8 (assertRigid, round 27);
     *   clean desired   -> net == R(360 - display) EXACTLY for rot != 90
     *                      classes; for the rot=90 family the r26 device
     *                      calibration post-applies R90: R(450 - display);
     *   mirror desired  -> net improper (a displayed mirror state), axis
     *                      pinned by the anchor test on the device dump ST.
     */
    private fun assertMandateNet(
        net: FloatArray,
        rotCwDeg: Int,
        desiredMirrorH: Boolean,
        display: Int,
        case: String,
    ) {
        assertRigid(net, case)
        val label = d4Label(net)
        val improper = label.startsWith("MH") || label == "MV"
        assertEquals("$case: displayed-mirror state", desiredMirrorH, improper)
        if (!desiredMirrorH) {
            val r90Bias = if (rotCwDeg == 90) 90 else 0
            assertEquals(
                "$case: clean net must be exactly R(360-display+r90Bias)",
                "R${((360 - display) + r90Bias) % 360}",
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
    fun `round-26 device anchors - class rot90 none display 0`() {
        // THE r26 constant (owner ladder: r24 270->180 off, r25 90->90 off,
        // r26 180 = the remaining value): front selfie uvRot=180/mirrorX=true,
        // back uvRot=180/mirrorX=false — same class, same uvRot, mirror-only
        // difference (the r25 90/90 pair was 90-off in opposite directions).
        val front = StOrientation.compensate(StClass(90, StMirror.NONE), desiredMirrorH = true, displayRotDeg = 0)
        assertEquals(180f, front.uvRotDeg, 0.01f)
        assertTrue(front.mirrorX)
        // Back camera: same 180, no mirror.
        val back = StOrientation.compensate(StClass(90, StMirror.NONE), desiredMirrorH = false, displayRotDeg = 0)
        assertEquals(180f, back.uvRotDeg, 0.01f)
        assertTrue(!back.mirrorX)
        val devSt = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        val frontNet = mmul(mrot(front.uvRotDeg.toInt()), mmul(if (front.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        // Device-calibrated front net (r26): canonical MV read 90-off on the
        // r25 build (up at RIGHT), so the correct net is R90 pre-multiplied:
        // R180 . MH . R90classLin = MH_R90. Back: R180 . R90classLin = R90
        // (r25's canonical R0 read up-at-LEFT — 90 CW off).
        assertRigid(frontNet, "front net (round-27 step 2)")
        assertEquals("MH_R90", d4Label(frontNet))
        assertEquals(StClass(90, StMirror.NONE), StOrientation.classify(devSt))

        // Back net through the chain MUST be R90 (the r26 device constant).
        val backNet = mmul(mrot(back.uvRotDeg.toInt()), mmul(if (back.mirrorX) MH else I, classLin(90, StMirror.NONE)))
        assertRigid(backNet, "back net (round-27 step 2)")
        assertEquals("R90", d4Label(backNet))

        // Video (dump: class rot=90 mirror=h) — SAME function, no own formula:
        // uvRot=180/mirrorX=true at display 0, net R90 (was canonical R0 in
        // r25 and read up-at-LEFT, same as back).
        val video = StOrientation.compensate(StClass(90, StMirror.H), desiredMirrorH = false, displayRotDeg = 0)
        assertEquals(180f, video.uvRotDeg, 0.01f)
        assertTrue(video.mirrorX)
        val videoNet = mmul(mrot(video.uvRotDeg.toInt()), mmul(if (video.mirrorX) MH else I, classLin(90, StMirror.H)))
        assertRigid(videoNet, "video net (round-27 step 2)")
        assertEquals("R90", d4Label(videoNet))
    }

    @Test
    fun `production net decoder names all 8 rigid transforms (round-27 step 2)`() {
        // Bridge test: the RUNTIME decoder (SourceUvMath.classifyNet — the one
        // that produced the r26 dump line OTHER[u=(0,1) v=(1,0)]) must name
        // ALL EIGHT D4 transforms. Production composition order: ST ->
        // mirrorX -> rotDeg (CCW). Name mapping: harness MH_R90 (the
        // transpose, u=(0,1) v=(1,0)) is DIAG_MIRROR; MH_R270 is
        // ANTI_DIAG_MIRROR.
        val identity = canonicalSt(0, StMirror.NONE)
        val rows = listOf(
            Triple(identity, false to 0f, "IDENTITY"),
            Triple(identity, true to 0f, "MIRROR_H"),
            Triple(canonicalSt(0, StMirror.V), false to 0f, "MIRROR_V"),
            Triple(canonicalSt(180, StMirror.NONE), false to 0f, "ROT180"),
            Triple(identity, false to 90f, "ROT90CCW"),
            Triple(identity, false to 270f, "ROT90CW"),
            Triple(identity, true to 270f, "DIAG_MIRROR"),
            Triple(identity, true to 90f, "ANTI_DIAG_MIRROR"),
        )
        for ((st, mr, expected) in rows) {
            assertEquals(expected, SourceUvMath.classifyNet(st, mr.second, mr.first))
        }
        // A genuinely non-rigid composition (shear) must be flagged, never
        // named. Shear 0.6: ny=0.51 escapes the legacy 0.3 near-tolerance
        // and det=0.86 escapes the rigid band (a 0.3 shear would still be
        // NAMED as its nearest rigid — tolerance is legacy round-16).
        val shear = floatArrayOf(
            1f, 0.6f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertTrue(SourceUvMath.classifyNet(shear, 0f, false).startsWith("NOT_RIGID"))
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
                            net, cls.rotCwDeg, desiredMirrorH, display,
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
