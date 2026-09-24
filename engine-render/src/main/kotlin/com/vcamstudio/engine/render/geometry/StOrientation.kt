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
 * [compensate] is the pure round-28 mandate formula: given the ST class and
 * the desired net orientation (back -> ROT_0 identity, front selfie ->
 * ROT_0 + horizontal mirror), derive the layer UV rotation and mirror:
 *
 *     base    = front ? (360 - rotCw) % 360 : rotCw   (rot cancels in the
 *                                                      opposite sense per facing)
 *     uvRot   = (base + (mirror == V ? 180 : 0) + 360) % 360
 *     mirrorX = (mirror != NONE) XOR front            (desired H only on front)
 *
 * Verified by StOrientationGoldenTest: 8 classes x {front, back} x
 * sensor fold {0, 90, 180, 270} = 64 cases, every case nets to the desired
 * orientation with exactly ONE (rot, mirrorX) compensation.
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
        val tl = pair(st[12], st[13])
        val tr = mapPoint(st, 1f, 0f)
        val bl = mapPoint(st, 0f, 1f)
        val du = pair(round1(tr.first - tl.first), round1(tr.second - tl.second))
        val dv = pair(round1(bl.first - tl.first), round1(bl.second - tl.second))
        return SIGNATURES[Triple(roundPair(tl), du, dv)]
    }

    /**
     * Round-28 mandate compensation: cancel the ST's rot/mirror and land the
     * desired net orientation. Back camera -> identity (upright, not
     * mirrored); front selfie -> horizontal mirror (upright, mirrored).
     *
     * Machine-verified over all 8 classes x 2 facings (StOrientationGoldenTest,
     * unique-hit proof): the sampling rot cancels the class rot in the OPPOSITE
     * sense per facing (back: sampling CCW-rot value == class CW rot; front:
     * mirrored target conjugates the cancel), and a V-mirrored ST class needs
     * an extra 180 (V == H + 180 in this decomposition). mirrorX is the XOR of
     * the desired (front-only H) and the ST's mirroredness.
     */
    fun compensate(cls: StClass, frontFacing: Boolean): StCompensation {
        val base = if (frontFacing) (360 - cls.rotCwDeg) % 360 else cls.rotCwDeg
        val vShift = if (cls.mirror == StMirror.V) 180 else 0
        val uvRot = ((base + vShift) % 360 + 360) % 360
        val mirrorX = (cls.mirror != StMirror.NONE) != frontFacing // XOR of mirrors
        return StCompensation(uvRot.toFloat(), mirrorX)
    }

    // ------------------------------------------------------------------ impl

    private fun mapPoint(st: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        Pair(st[0] * u + st[4] * v + st[12], st[1] * u + st[5] * v + st[13])

    private fun round1(x: Float): Int = Math.round(x)

    private fun pair(x: Float, y: Float): Pair<Float, Float> = Pair(x, y)

    private fun roundPair(p: Pair<Float, Float>): Pair<Int, Int> = Pair(round1(p.first), round1(p.second))

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
