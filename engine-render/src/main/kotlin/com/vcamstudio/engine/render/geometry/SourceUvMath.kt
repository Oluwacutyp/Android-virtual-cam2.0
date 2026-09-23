package com.vcamstudio.engine.render.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * Canonical source-orientation math for texture-sampling UVs — pure Kotlin,
 * no Android/GL types, so the golden tests run THIS exact code in CI
 * (SceneRenderer.bindSource calls [transformInto] in production).
 *
 * Pipeline, in sampling order, all anchored to the live UV window:
 *
 *  1. base window from [LayerGeometry] (fit/crop only — mirrors and source
 *     rotation deliberately do NOT live in geometry anymore);
 *  2. ST matrix of the external producer (SurfaceTexture: buffer flip +
 *     crop/scale). Never rotate before it — rotation∘flip != flip∘rotation,
 *     the flip conjugates a rotation into its inverse;
 *  3. mirrorX — post-ST, PRE-rotation: the post-ST frame is display-aligned,
 *     so mirroring there is a true horizontal display mirror. Mirroring
 *     AFTER the rotation operates in the rotated (transposed) frame and
 *     conjugates into a VERTICAL flip (machine-verified: the golden test
 *     caught exactly that mis-order at CI); a mirror composed before the ST
 *     matrix conjugates through the buffer flip the same way (the old
 *     geometry-stage bug — "front camera not consistently mirrored").
 *  4. rotation about the window center — counter-rotates the SAMPLING window
 *     so the buffer CONTENT reads upright (device-calibrated round 14:
 *     sampling angle == the source's metadata rotation, see PROGRESS.md);
 *  5. clamp to [0,1] — sampling an external OES texture outside [0,1] is
 *     undefined (the "diagonal black wedge" class). With correct math the
 *     clamp is a no-op; it exists so a future math bug can never wedge the
 *     preview again.
 */
object SourceUvMath {

    /**
     * Maps (u,v) through a column-major 4x4 matrix as produced by
     * SurfaceTexture.getTransformMatrix (affine: w row assumed [0,0,0,1]).
     * Returns `[u', v']`.
     */
    fun mapPoint(st: FloatArray, u: Float, v: Float): FloatArray =
        floatArrayOf(
            st[0] * u + st[4] * v + st[12],
            st[1] * u + st[5] * v + st[13],
        )

    /** Rotates (u,v) about (cu,cv) by [deg] degrees CCW in UV space. */
    fun rotatePoint(u: Float, v: Float, cu: Float, cv: Float, deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val ru = u - cu
        val rv = v - cv
        return floatArrayOf(cu + ru * c - rv * s, cv + ru * s + rv * c)
    }

    /** Mirrors u about cu (horizontal mirror in texture space). */
    fun mirrorU(u: Float, cu: Float): Float = 2f * cu - u

    /**
     * Full chain, in place into [dest] (dest may not alias [base]).
     * Window center = mean of the ST-mapped corners (correct under any
     * axis-aligned producer matrix, including crop/scale translations).
     * With [clamp] = false the raw values are kept — used by the golden
     * tests to prove the in-bounds invariant WITHOUT relying on the clamp.
     */
    fun transformInto(
        base: FloatArray,
        st: FloatArray?,
        rotDeg: Float,
        mirrorX: Boolean,
        dest: FloatArray,
        clamp: Boolean = true,
    ) {
        val n = base.size / 2
        var su = 0f
        var sv = 0f
        for (i in 0 until n) {
            var u = base[i * 2]
            var v = base[i * 2 + 1]
            if (st != null) {
                val m = mapPoint(st, u, v)
                u = m[0]
                v = m[1]
            }
            dest[i * 2] = u
            dest[i * 2 + 1] = v
            su += u
            sv += v
        }
        su /= n
        sv /= n
        for (i in 0 until n) {
            var u = dest[i * 2]
            var v = dest[i * 2 + 1]
            // Mirror FIRST (display-aligned post-ST frame -> horizontal
            // display mirror); mirroring after the rotation conjugates into
            // a vertical flip under 90-degree rotations.
            if (mirrorX) u = mirrorU(u, su)
            if (rotDeg != 0f) {
                val r = rotatePoint(u, v, su, sv, rotDeg)
                u = r[0]
                v = r[1]
            }
            dest[i * 2] = if (clamp) u.coerceIn(0f, 1f) else u
            dest[i * 2 + 1] = if (clamp) v.coerceIn(0f, 1f) else v
        }
    }

    /** Allocating variant (tests / one-off callers). */
    fun transform(
        base: FloatArray,
        st: FloatArray?,
        rotDeg: Float,
        mirrorX: Boolean,
        clamp: Boolean = true,
    ): FloatArray = FloatArray(base.size).also { transformInto(base, st, rotDeg, mirrorX, it, clamp) }

    /** The no-wedge invariant: every UV inside [0,1]. */
    fun inBounds(uvs: FloatArray): Boolean = uvs.all { it in 0f..1f }

    /**
     * DIAGNOSTIC (round 16, report-only): classifies the NET linear map the
     * pipeline applies in sampling space (ST -> mirror -> rotate composed).
     * Answers from a dump alone whether the source rotations cancel (e.g. a
     * producer ST that already carries a 90-degree rotation composed with
     * uvRot=270 nets to mirror/identity) or double-apply.
     */
    fun classifyNet(st: FloatArray?, rotDeg: Float, mirrorX: Boolean): String {
        val p0 = transform(floatArrayOf(0.5f, 0.5f), st, rotDeg, mirrorX, clamp = false)
        val px = transform(floatArrayOf(0.7f, 0.5f), st, rotDeg, mirrorX, clamp = false)
        val py = transform(floatArrayOf(0.5f, 0.7f), st, rotDeg, mirrorX, clamp = false)
        val ax = px[0] - p0[0] // image of +u
        val ay = px[1] - p0[1]
        val bx = py[0] - p0[0] // image of +v
        val by = py[1] - p0[1]
        fun near(v: Float, target: Float) = kotlin.math.abs(v - target) < 0.05f
        fun col(ax: Float, ay: Float, bx: Float, by: Float): String? = when {
            near(ax, 1f) && near(ay, 0f) && near(bx, 0f) && near(by, 1f) -> "IDENTITY"
            near(ax, -1f) && near(ay, 0f) && near(bx, 0f) && near(by, 1f) -> "MIRROR_H"
            near(ax, 1f) && near(ay, 0f) && near(bx, 0f) && near(by, -1f) -> "MIRROR_V"
            near(ax, -1f) && near(ay, 0f) && near(bx, 0f) && near(by, -1f) -> "ROT180"
            near(ax, 0f) && near(ay, 1f) && near(bx, -1f) && near(by, 0f) -> "ROT90CCW"
            near(ax, 0f) && near(ay, -1f) && near(bx, 1f) && near(by, 0f) -> "ROT90CW"
            else -> null
        }
        return col(ax, ay, bx, by)
            ?: ("OTHER[u=(" + ax + "," + ay + ") v=(" + bx + "," + by + ")]")
    }
}
