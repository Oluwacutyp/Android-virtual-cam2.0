package com.vcamstudio.app.ai

/**
 * r58: the pure numeric half of the face-swap pipeline (JVM-testable, no
 * Android imports). The ranges and templates are the r58 landmines:
 *  - ArcFace: CHW, v = (px - 127.5) / 127.5  -> [-1, 1]
 *  - INSwapper target: CHW, v = px / 255     -> [0, 1]
 * Both take ARGB ints (android Bitmap order: R = argb shr 16, G = 15,
 * B = 0) and emit CHW planes in RGB order.
 */
object SwapMath {

    const val ARC_SIZE = 112
    const val SWAP_SIZE = 128
    const val EMBED_DIM = 512
    const val EMAP_BYTES = EMBED_DIM * EMBED_DIM * 4 // 1_048_576

    /** ArcFace preprocess: ARGB ints -> CHW float, (px-127.5)/127.5. */
    fun arcfacePreprocess(argb: IntArray, out: FloatArray) {
        require(out.size >= 3 * ARC_SIZE * ARC_SIZE)
        val plane = ARC_SIZE * ARC_SIZE
        for (i in 0 until plane) {
            val px = argb[i]
            val r = (px shr 16 and 0xFF)
            val g = (px shr 8 and 0xFF)
            val b = (px and 0xFF)
            out[i] = (r - 127.5f) / 127.5f
            out[plane + i] = (g - 127.5f) / 127.5f
            out[2 * plane + i] = (b - 127.5f) / 127.5f
        }
    }

    /** INSwapper target preprocess: ARGB ints -> CHW float, px/255. */
    fun swapPreprocess(argb: IntArray, out: FloatArray) {
        require(out.size >= 3 * SWAP_SIZE * SWAP_SIZE)
        val plane = SWAP_SIZE * SWAP_SIZE
        for (i in 0 until plane) {
            val px = argb[i]
            out[i] = (px shr 16 and 0xFF) / 255f
            out[plane + i] = (px shr 8 and 0xFF) / 255f
            out[2 * plane + i] = (px and 0xFF) / 255f
        }
    }

    /**
     * L2-normalise IN PLACE. Returns the norm AFTER normalisation (must be
     * 1.0 for a correct embedding; logged with 6 dp).
     */
    fun l2norm(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        val n = kotlin.math.sqrt(s)
        if (n > 0f) {
            for (i in v.indices) v[i] /= n
        }
        var s2 = 0f
        for (x in v) s2 += x * x
        return kotlin.math.sqrt(s2)
    }

    /**
     * STAGE 3: latent = l2norm(latent x emap) with emap row-major [512][512].
     * Omitting emap swaps the WRONG IDENTITY and fails silently — the single
     * most dangerous mistake in this pipeline (r58 mandate).
     */
    fun project(latent: FloatArray, emap: FloatArray, out: FloatArray) {
        require(latent.size == EMBED_DIM && emap.size == EMBED_DIM * EMBED_DIM)
        require(out.size >= EMBED_DIM)
        for (j in 0 until EMBED_DIM) out[j] = 0f
        for (i in 0 until EMBED_DIM) {
            val lv = latent[i]
            if (lv == 0f) continue
            val row = i * EMBED_DIM
            for (j in 0 until EMBED_DIM) out[j] += lv * emap[row + j]
        }
    }

    /** The CONTROL source: the negated latent (deliberately wrong identity). */
    fun negate(v: FloatArray, out: FloatArray) {
        for (i in v.indices) out[i] = -v[i]
    }

    /** Structural emap assertion: 1,048,576 raw bytes -> (512,512) floats. */
    fun validEmapBytes(bytes: Int): Boolean = bytes == EMAP_BYTES && EMAP_BYTES / 4 == EMBED_DIM * EMBED_DIM

    /** Mean absolute error, swapped BGR planes vs the original ARGB crop. */
    fun maeBgr(swappedBgr: FloatArray, origArgb: IntArray): Float {
        val plane = SWAP_SIZE * SWAP_SIZE
        require(swappedBgr.size >= 3 * plane)
        var acc = 0.0
        for (i in 0 until plane) {
            val px = origArgb[i]
            val r = (px shr 16 and 0xFF).toFloat()
            val g = (px shr 8 and 0xFF).toFloat()
            val b = (px and 0xFF).toFloat()
            acc += kotlin.math.abs(swappedBgr[i] - b)            // plane 0 -> B
            acc += kotlin.math.abs(swappedBgr[plane + i] - g)    // plane 1 -> G
            acc += kotlin.math.abs(swappedBgr[2 * plane + i] - r) // plane 2 -> R
        }
        return (acc / (3.0 * plane)).toFloat()
    }
}
