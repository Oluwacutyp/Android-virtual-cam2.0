package com.vcamstudio.engine.aiface

/**
 * Phase 2 round: SCRFD detection result, normalized to the UPRIGHT camera
 * frame (rotation applied during preprocessing) — (0,0) top-left, (1,1)
 * bottom-right of that frame. The app layer mirrors x for front cameras
 * when surfacing the debug overlay.
 */
data class FaceBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val timestampMs: Long,
    /** Upright (rotation-applied) source frame dims, for overlay mapping. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

/**
 * One SCRFD detection in 640x640 letterbox input coordinates (pixels).
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
)

/**
 * Pure SCRFD decode + NMS (owner mandate: three strides, NMS 0.45,
 * score 0.5). Kept free of any ONNX/Android types so the math is
 * unit-testable — the same discipline as the golden ST harness.
 *
 * SCRFD outputs are distance maps at anchor centers. For stride s, the
 * grid is (640/s)x(640/s); scrfd_10g_bnkps uses 2 anchors per location on
 * stride 8 and 1 on strides 16/32 (anchor counts 12800/1600/400 for a
 * 640 input). Decode (distance2bbox):
 *   cx = (gx + 0.5) * s ; cy = (gy + 0.5) * s
 *   x1 = cx - d0*s ; y1 = cy - d1*s ; x2 = cx + d2*s ; y2 = cy + d3*s
 *
 * Output tensors are grouped by their LAST dimension: 1 = scores,
 * 4 = boxes, 10 = keypoints (unused this round). Within each group the
 * order corresponds to strides 8, 16, 32.
 */
object ScrfdPostprocess {

    const val SCORE_THRESHOLD = 0.5f
    const val NMS_IOU_THRESHOLD = 0.45f
    val STRIDES = intArrayOf(8, 16, 32)
    val ANCHORS_PER_STRIDE = intArrayOf(2, 1, 1)

    /** Flat score tensors per stride, each [N] (N = grid^2 * anchors). */
    fun decode(
        scores: List<FloatArray>,
        boxes: List<FloatArray>,
        inputSize: Int = 640,
    ): List<Detection> {
        val out = ArrayList<Detection>(256)
        for (s in scores.indices) {
            if (s >= boxes.size) break
            val stride = STRIDES[s]
            val anchors = ANCHORS_PER_STRIDE[s]
            val grid = inputSize / stride
            val sc = scores[s]
            val bx = boxes[s]
            val n = minOf(sc.size, bx.size / 4)
            var i = 0
            while (i < n) {
                val score = sc[i]
                if (score > SCORE_THRESHOLD) {
                    val gx = ((i / anchors) % grid).toFloat()
                    val gy = ((i / anchors) / grid).toFloat()
                    val cx = (gx + 0.5f) * stride
                    val cy = (gy + 0.5f) * stride
                    val d = i * 4
                    out.add(
                        Detection(
                            x1 = cx - bx[d] * stride,
                            y1 = cy - bx[d + 1] * stride,
                            x2 = cx + bx[d + 2] * stride,
                            y2 = cy + bx[d + 3] * stride,
                            score = score,
                        ),
                    )
                }
                i++
            }
        }
        return nms(out, NMS_IOU_THRESHOLD)
    }

    /** Standard greedy NMS by descending score. */
    fun nms(dets: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            keep.add(a)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(a, sorted[j]) > iouThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Splits ONNX outputs into (scores, boxes) lists by last-dim size.
     * [tensorShapes] entries are the shapes; [getters] materialize the
     * float payload for the matching index. Tensors with last-dim 10
     * (keypoints) are ignored this round.
     */
    fun groupOutputs(
        shapes: List<LongArray>,
        floats: (index: Int) -> FloatArray,
    ): Pair<List<FloatArray>, List<FloatArray>> {
        val scores = ArrayList<FloatArray>(3)
        val boxes = ArrayList<FloatArray>(3)
        shapes.forEachIndexed { i, shape ->
            val last = shape.last().toInt()
            when (last) {
                1 -> scores.add(floats(i))
                4 -> boxes.add(floats(i))
                else -> Unit // keypoints (10) — later rounds
            }
        }
        return scores to boxes
    }
}
