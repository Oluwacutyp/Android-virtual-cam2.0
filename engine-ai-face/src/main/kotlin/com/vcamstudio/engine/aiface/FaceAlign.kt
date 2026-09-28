package com.vcamstudio.engine.aiface

/**
 * r52a: pure similarity alignment for the swap chain (SCRFD 5 landmarks ->
 * 112 ArcFace crop / 128 inswapper crop). No Android imports — the same
 * unit-testable discipline as [ScrfdPostprocess].
 *
 * Semantics follow skimage SimilarityTransform.estimate as used by
 * insightface face_align.py: scale + rotation + translation, NO shear, NO
 * reflection. Closed form (complex least squares): with centred point sets
 * w_i = src_i - mean(src), d_i = dst_i - mean(dst) treated as complex
 * numbers, z = sum(conj(w) * d) / sum(|w|^2); scale = |z|, theta = arg(z).
 * This form cannot represent a reflection, which is exactly the constraint.
 *
 * Templates — arcface_dst VERIFIED against insightface face_align.py
 * (current master): order is left eye, right eye, nose, left mouth corner,
 * right mouth corner. Scaling rule (owner-verified):
 *   image_size % 112 == 0 -> ratio = size/112, diff_x = 0
 *   otherwise            -> ratio = size/128, diff_x = 8.0 * ratio
 * so 112 uses the raw template and 128 shifts it +8 px on x (NOT x*128/112).
 */
object FaceAlign {

    /** arcface_dst, flattened x,y pairs. */
    val ARCFACE_DST = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /** Template landmark positions for a given crop size (10 floats). */
    fun template(imageSize: Int): FloatArray {
        val ratio: Float
        val diffX: Float
        if (imageSize % 112 == 0) {
            ratio = imageSize / 112f
            diffX = 0f
        } else {
            ratio = imageSize / 128f
            diffX = 8f * ratio
        }
        val out = FloatArray(10)
        for (i in 0 until 5) {
            out[2 * i] = ARCFACE_DST[2 * i] * ratio + diffX
            out[2 * i + 1] = ARCFACE_DST[2 * i + 1] * ratio
        }
        return out
    }

    /** 2x3 row-major affine [a, b, tx, c, d, ty]: dst = M * [x, y, 1]^T. */
    data class Affine(val m: FloatArray) {
        fun map(x: Float, y: Float): Pair<Float, Float> = Pair(
            m[0] * x + m[1] * y + m[2],
            m[3] * x + m[4] * y + m[5],
        )

        fun map(pts: FloatArray): FloatArray {
            val out = FloatArray(pts.size)
            var i = 0
            while (i + 1 < pts.size) {
                val (mx, my) = map(pts[i], pts[i + 1])
                out[i] = mx
                out[i + 1] = my
                i += 2
            }
            return out
        }

        /** Inverse mapping (paste-back). Null when (near-)singular. */
        fun inverse(): Affine? {
            val det = m[0] * m[4] - m[1] * m[3]
            if (kotlin.math.abs(det) < 1e-12f) return null
            val a = m[4] / det
            val b = -m[1] / det
            val c = -m[3] / det
            val d = m[0] / det
            return Affine(floatArrayOf(a, b, -(a * m[2] + b * m[5]), c, d, -(c * m[2] + d * m[5])))
        }
    }

    /**
     * Estimates the similarity transform mapping [src] (10 floats: 5
     * detected landmarks, upright PIXEL coordinates) onto the template for
     * [imageSize]. Null when the point set is degenerate (all points equal).
     */
    fun estimate(src: FloatArray, imageSize: Int): Affine? {
        if (src.size < 10) return null
        val dst = template(imageSize)
        var sx = 0f
        var sy = 0f
        var dx = 0f
        var dy = 0f
        for (i in 0 until 5) {
            sx += src[2 * i]; sy += src[2 * i + 1]
            dx += dst[2 * i]; dy += dst[2 * i + 1]
        }
        sx /= 5f; sy /= 5f; dx /= 5f; dy /= 5f
        // Complex least squares on centred sets: z = sum(conj(w)*d)/sum(|w|^2).
        var numRe = 0f
        var numIm = 0f
        var den = 0f
        for (i in 0 until 5) {
            val wx = src[2 * i] - sx
            val wy = src[2 * i + 1] - sy
            val vx = dst[2 * i] - dx
            val vy = dst[2 * i + 1] - dy
            numRe += wx * vx + wy * vy   // Re(conj(w) * d)
            numIm += wx * vy - wy * vx   // Im(conj(w) * d)
            den += wx * wx + wy * wy
        }
        if (den < 1e-9f) return null
        val scale = kotlin.math.sqrt(numRe * numRe + numIm * numIm) / den
        val theta = kotlin.math.atan2(numIm, numRe)
        val c = scale * kotlin.math.cos(theta)
        val s = scale * kotlin.math.sin(theta)
        // t = dst_centroid - R*scale * src_centroid
        val tx = dx - (c * sx - s * sy)
        val ty = dy - (s * sx + c * sy)
        return Affine(floatArrayOf(c, -s, tx, s, c, ty))
    }
}
