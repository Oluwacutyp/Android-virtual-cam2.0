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
    /**
     * r52a: 5 keypoints, same 640-letterbox pixel space as the box, order
     * left eye, right eye, nose, left mouth corner, right mouth corner.
     * ADDITIVE — zeros when the model emitted no kps tensor for the pair.
     */
    val landmarks: FloatArray = FloatArray(10),
) {
    // landmarks is an array: exclude it from equals/hashCode so the existing
    // value-semantics of Detection (box/score only) are unchanged.
    override fun equals(other: Any?): Boolean =
        other is Detection && other.x1 == x1 && other.y1 == y1 &&
            other.x2 == x2 && other.y2 == y2 && other.score == score

    override fun hashCode(): Int = java.util.Objects.hash(x1, y1, x2, y2, score)
}

/**
 * Pure SCRFD decode + NMS (owner mandate: three strides, NMS 0.45,
 * score 0.5). Kept free of any ONNX/Android types so the math is
 * unit-testable — the same discipline as the golden ST harness.
 *
 * SCRFD outputs are distance maps at anchor centers. For stride s, the
 * grid is (640/s)x(640/s); decode (distance2bbox):
 *   cx = (gx + 0.5) * s ; cy = (gy + 0.5) * s
 *   x1 = cx - d0*s ; y1 = cy - d1*s ; x2 = cx + d2*s ; y2 = cy + d3*s
 *
 * Output tensors are grouped by their LAST dimension: 1 = scores,
 * 4 = boxes, 10 = keypoints (unused this round). r51: each pair's
 * (stride, anchors) is derived from its ENTRY COUNT via
 * [resolveLayout] — the original output-position assumption
 * (stride order 8/16/32, anchors 2/1/1) does not match the actual
 * scrfd_10g_bnkps export, which carries TWO anchors at every stride
 * (12800/3200/800 entries for a 640 input); decoding the stride-16
 * tensor on a 40-grid with A=1 drove gy to 79 and cy to 1272 — boxes
 * pinned past the frame bottom while scores stayed plausible (the
 * dump-5/6 device signature).
 */
object ScrfdPostprocess {

    const val SCORE_THRESHOLD = 0.5f
    const val NMS_IOU_THRESHOLD = 0.45f

    /**
     * r51: identifies (stride, anchors) from an output pair's entry count:
     * entries = grid^2 * anchors with grid = inputSize/stride. Strides are
     * probed smallest-first so ties resolve toward the smaller stride;
     * anchors are capped at 8. Returns null for entry counts that fit no
     * layout — [decode] SKIPS such pairs rather than guessing.
     */
    fun resolveLayout(entries: Int, inputSize: Int): Pair<Int, Int>? {
        for (stride in intArrayOf(8, 16, 32)) {
            val grid = inputSize / stride
            val cells = grid * grid
            if (entries < cells || entries % cells != 0) continue
            val anchors = entries / cells
            if (anchors in 1..8) return stride to anchors
        }
        return null
    }

    /** Flat score tensors per stride, each [N] (N = grid^2 * anchors). */
    fun decode(
        scores: List<FloatArray>,
        boxes: List<FloatArray>,
        inputSize: Int = 640,
        keypoints: List<FloatArray> = emptyList(),
    ): List<Detection> {
        val out = ArrayList<Detection>(256)
        for (s in scores.indices) {
            if (s >= boxes.size) break
            val sc = scores[s]
            val bx = boxes[s]
            val n = minOf(sc.size, bx.size / 4)
            val (stride, anchors) = resolveLayout(n, inputSize) ?: continue
            val grid = inputSize / stride
            // r52a: the kps tensor for this pair carries n*10 floats (5
            // landmarks x 2 per anchor). Matched by ENTRY COUNT — the
            // r51.1 lesson: never by output position.
            val kp = keypoints.firstOrNull { it.size == n * 10 }
            var i = 0
            while (i < n) {
                val score = sc[i]
                if (score > SCORE_THRESHOLD) {
                    val gx = ((i / anchors) % grid).toFloat()
                    val gy = ((i / anchors) / grid).toFloat()
                    val cx = (gx + 0.5f) * stride
                    val cy = (gy + 0.5f) * stride
                    val d = i * 4
                    val lm = FloatArray(10)
                    if (kp != null) {
                        val k = i * 10
                        for (j in 0 until 5) {
                            lm[2 * j] = cx + kp[k + 2 * j] * stride
                            lm[2 * j + 1] = cy + kp[k + 2 * j + 1] * stride
                        }
                    }
                    out.add(
                        Detection(
                            x1 = cx - bx[d] * stride,
                            y1 = cy - bx[d + 1] * stride,
                            x2 = cx + bx[d + 2] * stride,
                            y2 = cy + bx[d + 3] * stride,
                            score = score,
                            landmarks = lm,
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

    /** Scores, boxes and r52a keypoints, grouped by last-dim size. */
    data class Grouped(
        val scores: List<FloatArray>,
        val boxes: List<FloatArray>,
        val kps: List<FloatArray>,
    )

    /**
     * Splits ONNX outputs into score/box/kps lists by last-dim size.
     * [tensorShapes] entries are the shapes; [floats] materializes the
     * float payload for the matching index. Last-dim 10 = r52a keypoints
     * (5 landmarks x 2), previously discarded.
     */
    fun groupOutputs(
        shapes: List<LongArray>,
        floats: (index: Int) -> FloatArray,
    ): Grouped {
        val scores = ArrayList<FloatArray>(3)
        val boxes = ArrayList<FloatArray>(3)
        val kps = ArrayList<FloatArray>(3)
        shapes.forEachIndexed { i, shape ->
            val last = shape.last().toInt()
            when (last) {
                1 -> scores.add(floats(i))
                4 -> boxes.add(floats(i))
                10 -> kps.add(floats(i))
                else -> Unit
            }
        }
        return Grouped(scores, boxes, kps)
    }
}
