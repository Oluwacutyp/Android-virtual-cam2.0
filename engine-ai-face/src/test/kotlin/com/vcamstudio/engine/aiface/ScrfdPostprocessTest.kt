package com.vcamstudio.engine.aiface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-math checks for the SCRFD decode + NMS (Phase 2). Same discipline as
 * the R28 golden ST harness: exact, synthetic, no device dependency.
 */
class ScrfdPostprocessTest {

    private fun feq(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) < eps

    @Test
    fun `layout resolves two anchors per stride for the real export`() {
        // r51 correction: scrfd_10g_bnkps carries TWO anchors at EVERY
        // stride (12800/3200/800 entries for a 640 input). The previous
        // assertion here (1600/400) encoded the wrong [2,1,1] assumption
        // that pinned device boxes to the frame bottom.
        assertEquals(8 to 2, ScrfdPostprocess.resolveLayout(12800, 640))
        assertEquals(16 to 2, ScrfdPostprocess.resolveLayout(3200, 640))
        assertEquals(32 to 2, ScrfdPostprocess.resolveLayout(800, 640))
    }

    @Test
    fun `layout resolves one-anchor variant and rejects unknown counts`() {
        assertEquals(8 to 1, ScrfdPostprocess.resolveLayout(6400, 640))
        assertEquals(16 to 1, ScrfdPostprocess.resolveLayout(1600, 640))
        assertEquals(32 to 1, ScrfdPostprocess.resolveLayout(400, 640))
        assertEquals(null, ScrfdPostprocess.resolveLayout(12345, 640))
        assertEquals(null, ScrfdPostprocess.resolveLayout(3, 640))
    }

    @Test
    fun decode_centres_the_box_on_the_anchor_that_scored() {
        val grid = 80          // 640 / 8
        val anchors = 2
        val n = grid * grid * anchors
        val scores = FloatArray(n)
        val boxes = FloatArray(n * 4)
        val pos = 40 * grid + 20          // gx=20, gy=40
        val i = pos * anchors             // first anchor at that position
        scores[i] = 0.9f
        // 4 px each side, expressed in stride units
        boxes[i * 4 + 0] = 4f / 8f
        boxes[i * 4 + 1] = 4f / 8f
        boxes[i * 4 + 2] = 4f / 8f
        boxes[i * 4 + 3] = 4f / 8f
        val dets = ScrfdPostprocess.decode(listOf(scores), listOf(boxes), 640)
        assertEquals(1, dets.size)
        val d = dets[0]
        // anchor centre = ((20+0.5)*8, (40+0.5)*8) = (164, 324)
        assertEquals(160f, d.x1, 0.5f)
        assertEquals(320f, d.y1, 0.5f)
        assertEquals(168f, d.x2, 0.5f)
        assertEquals(328f, d.y2, 0.5f)
    }

    @Test
    fun `decode resolves stride16 two-anchor layout (dump-6 bug)`() {
        // The device bug: the stride-16 tensor (3200 entries, 2 anchors)
        // was decoded as 40-grid A=1 — gy ran to 79, cy to 1272.
        val n = 3200
        val scores = FloatArray(n)
        val boxes = FloatArray(n * 4)
        val grid = 40
        val i = (25 * grid + 10) * 2      // gx=10, gy=25, anchor 0
        scores[i] = 0.8f
        boxes[i * 4 + 0] = 4f / 16f
        boxes[i * 4 + 1] = 4f / 16f
        boxes[i * 4 + 2] = 4f / 16f
        boxes[i * 4 + 3] = 4f / 16f
        val dets = ScrfdPostprocess.decode(listOf(scores), listOf(boxes), 640)
        assertEquals(1, dets.size)
        // anchor centre = (168, 408); 4 px each side
        assertEquals(164f, dets[0].x1, 0.5f)
        assertEquals(404f, dets[0].y1, 0.5f)
        assertEquals(172f, dets[0].x2, 0.5f)
        assertEquals(412f, dets[0].y2, 0.5f)
    }

    @Test
    fun `decode maps center anchor distance to expected box`() {
        // stride 8, grid 80: anchor index for grid (40,50) with 2 anchors/location:
        // location = 50*80+40 = 4040 -> anchor i in [8080, 8081)
        val n = 12800
        val scores = FloatArray(n)
        val boxes = FloatArray(n * 4)
        val loc = 50 * 80 + 40
        val i = loc * 2 // anchor 0
        scores[i] = 0.9f
        // distances in stride units: d0=1.5, d1=0.5, d2=2.0, d3=1.0
        boxes[i * 4 + 0] = 1.5f
        boxes[i * 4 + 1] = 0.5f
        boxes[i * 4 + 2] = 2.0f
        boxes[i * 4 + 3] = 1.0f
        val dets = ScrfdPostprocess.decode(listOf(scores), listOf(boxes))
        assertEquals(1, dets.size)
        val cx = (40 + 0.5f) * 8
        val cy = (50 + 0.5f) * 8
        assertTrue(feq(cx - 1.5f * 8, dets[0].x1))
        assertTrue(feq(cy - 0.5f * 8, dets[0].y1))
        assertTrue(feq(cx + 2.0f * 8, dets[0].x2))
        assertTrue(feq(cy + 1.0f * 8, dets[0].y2))
        assertTrue(feq(0.9f, dets[0].score))
    }

    @Test
    fun `decode respects score threshold`() {
        val scores = FloatArray(12800).also { it[0] = 0.49f }
        val boxes = FloatArray(12800 * 4)
        val dets = ScrfdPostprocess.decode(listOf(scores), listOf(boxes))
        assertTrue(dets.isEmpty())
    }

    @Test
    fun `nms suppresses overlaps keeps separated`() {
        fun det(x1: Float, score: Float) = Detection(x1, 10f, x1 + 100f, 110f, score)
        val dets = listOf(det(0f, 0.7f), det(8f, 0.9f), det(500f, 0.5f))
        val kept = ScrfdPostprocess.nms(dets, 0.45f)
        assertEquals(2, kept.size)
        assertEquals(0.9f, kept[0].score, 1e-6f)
    }

    @Test
    fun `groupOutputs classifies by last dim`() {
        val shapes = listOf(
            longArrayOf(1, 12800, 1), longArrayOf(1, 3200, 1), longArrayOf(1, 800, 1),
            longArrayOf(1, 12800, 4), longArrayOf(1, 3200, 4), longArrayOf(1, 800, 4),
            longArrayOf(1, 12800, 10), longArrayOf(1, 3200, 10), longArrayOf(1, 800, 10),
        )
        val g = ScrfdPostprocess.groupOutputs(shapes) { idx ->
            FloatArray(shapes[idx][1].toInt() * shapes[idx][2].toInt())
        }
        assertEquals(3, g.scores.size)
        assertEquals(3, g.boxes.size)
        assertEquals(3, g.kps.size)
        assertEquals(12800, g.scores[0].size)
        assertEquals(800, g.scores[2].size)
        assertEquals(12800 * 4, g.boxes[0].size)
        assertEquals(128000, g.kps[0].size)
    }

    @Test
    fun `decode attaches five keypoints to the scoring anchor`() {
        // r52a: stride-16, 2 anchors — the layout the real export uses.
        // Anchor at gx=10, gy=25; kps distances in stride units per point:
        // (-2,-1) (2,-1) (0,0) (-1,2) (1,2).
        val n = 3200
        val grid = 40
        val scores = FloatArray(n)
        val boxes = FloatArray(n * 4)
        val kps = FloatArray(n * 10)
        val i = (25 * grid + 10) * 2
        scores[i] = 0.8f
        val k = i * 10
        val offs = arrayOf(
            floatArrayOf(-2f, -1f), floatArrayOf(2f, -1f), floatArrayOf(0f, 0f),
            floatArrayOf(-1f, 2f), floatArrayOf(1f, 2f),
        )
        for (j in 0 until 5) {
            kps[k + 2 * j] = offs[j][0]
            kps[k + 2 * j + 1] = offs[j][1]
        }
        val dets = ScrfdPostprocess.decode(listOf(scores), listOf(boxes), 640, listOf(kps))
        assertEquals(1, dets.size)
        val cx = (10 + 0.5f) * 16
        val cy = (25 + 0.5f) * 16
        val lm = dets[0].landmarks
        for (j in 0 until 5) {
            assertEquals(cx + offs[j][0] * 16, lm[2 * j], 0.01f)
            assertEquals(cy + offs[j][1] * 16, lm[2 * j + 1], 0.01f)
        }
        // Anchor centre = (168, 408): eyes above nose above mouth on y.
        assertTrue(lm[1] < lm[5] && lm[5] < lm[7])
    }
}
