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
 * [compensate] is the pure mandate formula (round-25 sign, round-26
 * device-calibrated bias): given the ST class, the desired mirror (front
 * selfie only) and the display rotation, derive the layer UV rotation and
 * mirror:
 *
 *     mx    = (mirror != NONE) XOR desiredMirrorH
 *     uvRot = (rotCw - display + (rotCw == 90 ? 90 : 0)
 *              + (mirror == V ? 180 : 0)) mod 360
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
     * Round-26 mandate (owner "ROUND 26 — ROTATION. ONE CONSTANT. ONE
     * CHANGE") — supersedes the r25 constant for the rot=90 family. The
     * rot cancel stays the r25 swapped order, ONE branch for ALL sources
     * (cameras AND video), ONE device-calibration bias:
     *
     *     mx    = (st mirror != NONE) XOR desiredMirrorH
     *     uvRot = (rotCw - displayDeg + ROT90_FAMILY_BIAS
     *              + (mirror == V ? 180 : 0) + 360) % 360
     *
     *     ROT90_FAMILY_BIAS = 90 if rotCw == 90 else 0
     *
     * Device evidence (r25 build, owner report): front (class 90/none,
     * uvRot=90) showed up-at-RIGHT (90 CCW off); back (same class,
     * uvRot=90) showed up-at-LEFT (90 CW off); video (class 90/h, same
     * function) showed up-at-LEFT. Same 90 pre-mirror residual on every
     * rot=90 chain — the mirror flips its display direction. Owner ladder:
     * r24 uvRot=270 -> 180 off; r25 uvRot=90 -> 90 off; r26 uvRot=180
     * (bias 90->uvRot lands 180 at display 0 for the whole rot=90 family:
     * front 180/true, back 180/false, video 180/true). Classes with
     * rotCw != 90 are UNCHANGED from r25 (machine-checked byte-identical
     * over the non-90 cases). A V-mirrored ST class still needs its extra
     * 180 (V == H + 180 in this decomposition).
     *
     * desiredMirrorH: front selfie = true (upright + horizontally mirrored);
     * back camera and video = false (upright, not mirrored). displayRotDeg =
     * Display.getRotation() * 90, re-read on every configuration change
     * (mandate 2) and folded into desired_net.rot; mirror unchanged by it.
     */
    fun compensate(cls: StClass, desiredMirrorH: Boolean, displayRotDeg: Int): StCompensation {
        val mx = (cls.mirror != StMirror.NONE) != desiredMirrorH
        val display = ((displayRotDeg % 360) + 360) % 360
        val vShift = if (cls.mirror == StMirror.V) 180 else 0
        val r90Bias = if (cls.rotCwDeg == 90) 90 else 0
        val uvRot = ((cls.rotCwDeg - display + r90Bias + vShift) % 360 + 360) % 360
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
