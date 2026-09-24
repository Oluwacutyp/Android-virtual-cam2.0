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
 * [compensate] is the pure mandate formula (round-25 sign, device
 * calibrated): given the ST class, the desired mirror (front selfie only)
 * and the display rotation, derive the layer UV rotation and mirror:
 *
 *     mx    = (mirror != NONE) XOR desiredMirrorH
 *     uvRot = mx ? (rotCw - display) : (display - rotCw)   (mod 360)
 *     uvRot = (uvRot + (mirror == V ? 180 : 0)) mod 360
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
     * Round-25 mandate (owner "ROUND 25 — ROTATION. SIGN FLIP + VIDEO
     * ROUTING") — supersedes the r28 sign. The rot cancel is the mandate's
     * literal swapped order, ONE branch for ALL sources (cameras AND video):
     *
     *     mx    = (st mirror != NONE) XOR desiredMirrorH
     *     uvRot = (rotCw - displayDeg + (mirror == V ? 180 : 0) + 360) % 360
     *
     * Device-calibrated (round-25 report, dump ST class rot=90/none):
     * front -> uvRot=90/mirrorX=true (was 270/true in r28 — the whole flip);
     * back and video net to CANONICAL IDENTITY (composed net = R(-display):
     * upright in any display frame, the strongest anchor there is). A
     * V-mirrored ST class needs the extra 180 (V == H + 180 in this
     * decomposition); a V class at display 0 with clean desired nets
     * identity only with that shift (machine-checked over 256 cases).
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
        val uvRot = ((cls.rotCwDeg - display + vShift) % 360 + 360) % 360
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
