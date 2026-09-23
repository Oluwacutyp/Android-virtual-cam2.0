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
 *  3. rotation about the window center — counter-rotates the SAMPLING window
 *     so the buffer CONTENT reads upright (device-calibrated round 14:
 *     sampling angle == the source's metadata rotation, see PROGRESS.md);
 *  4. mirrorX — post-ST ONLY. A mirror composed before the ST flip
 *     conjugates into a VERTICAL image flip (flip∘mirrorH == mirrorV∘flip),
 *     which is exactly the "front camera not consistently mirrored" bug;
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
            if (rotDeg != 0f) {
                val r = rotatePoint(u, v, su, sv, rotDeg)
                u = r[0]
                v = r[1]
            }
            if (mirrorX) u = mirrorU(u, su)
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
}
