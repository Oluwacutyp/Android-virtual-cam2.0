package com.vcamstudio.engine.render.geometry

/**
 * Round-28 (owner "ROUND 24 — ROTATION"): ST-class orientation algebra.
 *
 * The producer-provided SurfaceTexture transform (ST) decomposes — at bind /
 * every ST change — into one of EIGHT canonical classes:
 *
 *     StClass(rotCwDeg ∈ {0, 90, 180, 270}, mirror ∈ {NONE, H, V})
 *
 * The decomposition is canonicalized as "mirror in texture space FIRST, then
 * rotate CW"; H and V collapse to distinct rot/mirror pairs so exactly 8
 * classes cover every axis-aligned orientation (dihedral group D4). The
 * classifier is a pure corner-probe: map the window corners (TL, TR, BL)
 * through the ST and match the resulting (TL, du, dv) signature. NEVER a
 * hardcoded sensor/table guess — the ST is read fresh every time.
 *
 * [transformFromST] is the pure derivation (round-28 mandate, ONE function,
 * sole source of truth): given the ST matrix (or its class), isFront and
 * the display rotation, derive the layer UV rotation and mirror:
 *
 *     mirrorX = (mirror != NONE) XOR isFront
 *     uvRot   = (rotCw - display - 90 + (mirror == V ? 180 : 0)) mod 360
 *
 * Verified by StOrientationGoldenTest: 8 classes x {mirror, clean} x
 * sensor fold {0, 90, 180, 270} x display {0, 90, 180, 270} = 256 cases.
 */
enum class StMirror(val tag: String) {
    NONE("none"),
    H("h"),
    V("v");

    companion object {
        fun fromTag(tag: String): StMirror? = entries.firstOrNull { it.tag == tag }
    }
}

/** Canonical orientation class of a producer ST (see [StOrientation]). */
data class StClass(val rotCwDeg: Int, val mirror: StMirror) {
    /** Log-contract label: `rot=<n> mirror=<none|h|v>`. */
    fun label(): String = "rot=$rotCwDeg mirror=${mirror.tag}"
}

/** Layer UV compensation derived from an [StClass] (pure data, no behavior). */
data class StCompensation(val uvRotDeg: Float, val mirrorX: Boolean)

object StOrientation {

    /**
     * Classifies a column-major 4x4 ST (as emitted by
     * SurfaceTexture.getTransformMatrix) into one of the 8 canonical classes,
     * or null when the matrix is not a pure axis-aligned orientation (crop,
     * scale, shear -> caller logs ST_CLASS_UNCLASSIFIED).
     */
    fun classify(st: FloatArray): StClass? {
        if (st.size < 16) return null
        val tlx = Math.round(st[12])
        val tly = Math.round(st[13])
        val tr = mapPoint(st, 1f, 0f)
        val bl = mapPoint(st, 0f, 1f)
        val key = Triple(
            Pair(tlx, tly),
            Pair(Math.round(tr.first - st[12]), Math.round(tr.second - st[13])),
            Pair(Math.round(bl.first - st[12]), Math.round(bl.second - st[13])),
        )
        return SIGNATURES[key]
    }

    /**
     * Round-28 mandate (owner "STOP GUESSING CONSTANTS. THE PIPELINE
     * CONTRACT IS BROKEN. RESTRUCTURE.") — ONE derivation function, the SOLE
     * source of truth for uvRot/mirrorX. No compose-on-top pathway anymore.
     *
     *     mirrorX = (st mirror != NONE) XOR isFront
     *     vShift  = if (st mirror == V) 180 else 0
     *     uvRot   = (stRotCw - displayRotation - 90 + vShift) mod 360
     *
     * The (−90) is the DEVICE DISPLAY FRAME constant, derived — not guessed —
     * from the owner's own calibration reports; all of them fit ONE model:
     *
     *     displayed(X) = R180 · X   (the compositor pre-rotates by R180)
     *     upright clean  source reads canonical R90
     *     upright mirror source reads canonical MH_R90
     *
     * Consistency (canonical nets): r24 front net MH -> displayed R180·MH =
     * MV = upright-mirror·R180 => "180 off" ✓; r25 back net R0 -> displayed
     * R180 = upright-clean·R270 => "90 off" ✓; r25 front net MV -> displayed
     * MH = upright-mirror·R90 => "90 off" ✓; r26 nets MH_R90 / R90 ->
     * displayed ANTI / R270 = upright·R180 => "180 off" ✓. The derivation
     * lands uvRot=0 for this device's rot=90 family at display 0 (front
     * 0/true, back 0/false, video 0/true) — the ladder end, now a theorem
     * inside the function.
     *
     * Composition ORDER (verified by StOrientationGoldenTest): the layer
     * applies ST -> mirrorX -> uvRot (SourceUvMath.transformInto); the
     * closed-form laws are: net rigid ALWAYS; net improper IFF isFront;
     * clean net == R((270 - display) mod 360) for EVERY ST class.
     *
     * isFront: front selfie = true (upright + mirrored on device); back and
     * video = false. displayRotation = Display.getRotation() * 90, re-read
     * on every configuration change.
     */
    fun transformFromST(
        stMatrix: FloatArray,
        isFront: Boolean,
        displayRotation: Int,
    ): StCompensation? {
        val cls = classify(stMatrix) ?: return null
        return transformFromClass(cls, isFront, displayRotation)
    }

    /**
     * The class-based entry of the SAME derivation (the engine callback path
     * already carries the decomposed class). ONE core — no separate formula
     * for cameras vs video (round-25/28 mandate).
     */
    fun transformFromClass(
        cls: StClass,
        isFront: Boolean,
        displayRotation: Int,
    ): StCompensation {
        val display = ((displayRotation % 360) + 360) % 360
        val mx = (cls.mirror != StMirror.NONE) != isFront
        val vShift = if (cls.mirror == StMirror.V) 180 else 0
        val uvRot = ((cls.rotCwDeg - display - 90 + vShift) % 360 + 360) % 360
        return StCompensation(uvRot.toFloat(), mx)
    }

    // ------------------------------------------------------------------ impl

    private fun mapPoint(st: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        Pair(st[0] * u + st[4] * v + st[12], st[1] * u + st[5] * v + st[13])

    // Canonical corner signatures: (TL, du = TL->TR, dv = TL->BL) -> class.
    // Mirror is applied in texture space first, then the CW rotation; every
    // axis-aligned orientation has exactly ONE signature in this table.
    private val SIGNATURES: Map<Triple<Pair<Int, Int>, Pair<Int, Int>, Pair<Int, Int>>, StClass> = buildMap {
        fun sig(tl: Pair<Int, Int>, du: Pair<Int, Int>, dv: Pair<Int, Int>, rot: Int, m: StMirror) {
            put(Triple(tl, du, dv), StClass(rot, m))
        }
        sig((0 to 0), (1 to 0), (0 to 1), 0, StMirror.NONE) // identity
        sig((1 to 0), (-1 to 0), (0 to 1), 0, StMirror.H) // u -> 1-u
        sig((0 to 1), (1 to 0), (0 to -1), 0, StMirror.V) // v -> 1-v (SurfaceTexture flipY)
        sig((0 to 1), (0 to -1), (1 to 0), 90, StMirror.NONE) // (u,v) -> (v, 1-u): content 90 CW
        sig((1 to 1), (0 to -1), (-1 to 0), 90, StMirror.H)
        sig((0 to 0), (0 to 1), (1 to 0), 90, StMirror.V)
        sig((1 to 1), (-1 to 0), (0 to -1), 180, StMirror.NONE)
        sig((1 to 0), (0 to 1), (-1 to 0), 270, StMirror.NONE) // (u,v) -> (1-v, u): content 90 CCW
    }
}
