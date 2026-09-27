package com.vcamstudio.engine.aiface

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 49: the ONE YUV->tensor implementation shared by the parked
 * main-process path and the :ai child. Tests pin the letterbox geometry
 * (mandate 2.6): scale=min(640/w,640/h), grey padding -> 0.0 after
 * (v-127.5)/128, rotation folded into upright->sensor sampling.
 */
class ScrfdPreprocessTest {

    private fun tensor(): FloatBuffer =
        ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Packed I420 buffer of w x h filled with constants. */
    private fun i420(w: Int, h: Int, y: Int, u: Int, v: Int): ByteBuffer {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val buf = ByteBuffer.allocateDirect(w * h + 2 * cw * ch).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) buf.put(y.toByte())
        for (i in 0 until cw * ch) buf.put(u.toByte())
        for (i in 0 until cw * ch) buf.put(v.toByte())
        buf.position(0)
        return buf
    }

    @Test
    fun `letterbox 640x480 at size 640 fills full width`() {
        val src = i420(640, 480, 235, 128, 128) // white luma, neutral chroma
        val out = tensor()
        val lb = ScrfdPreprocess.fill(src, 640, 480, 0, out)
        assertNotNull(lb)
        lb!!
        assertEquals(1f, lb.scale, 1e-5f)
        assertEquals(0f, lb.padX, 1e-5f)
        assertEquals(80f, lb.padY, 1e-5f) // (640 - 480*1) / 2
        assertEquals(640, lb.uprightW)
        assertEquals(480, lb.uprightH)
        // Center pixel is white in all channels: (255-127.5)/128.
        val expected = (235f - 127.5f) / 128f
        assertEquals(expected, out.get(320 * 640 + 320), 0.02f)
        assertEquals(expected, out.get(640 * 640 + 320 * 640 + 320), 0.02f)
        assertEquals(expected, out.get(2 * 640 * 640 + 320 * 640 + 320), 0.02f)
        // Top row (pad region) is grey 127.5 -> exactly 0.0.
        assertEquals(0f, out.get(5), 1e-6f)
        assertEquals(0f, out.get(640 * 640 + 5), 1e-6f)
        assertEquals(0f, out.get(2 * 640 * 640 + 5), 1e-6f)
    }

    @Test
    fun `letterbox 480x640 upright is portrait`() {
        val src = i420(480, 640, 128, 128, 128)
        val out = tensor()
        val lb = ScrfdPreprocess.fill(src, 480, 640, 0, out)
        assertNotNull(lb)
        lb!!
        // Rotation 0: upright dims equal SOURCE dims — a 480-wide,
        // 640-tall frame is already portrait (the swapped 640x480 case is
        // covered by the rot90 test below).
        assertEquals(480, lb.uprightW)
        assertEquals(640, lb.uprightH)
        assertEquals(1f, lb.scale, 1e-5f)
        assertEquals(80f, lb.padX, 1e-5f)
        assertEquals(0f, lb.padY, 1e-5f)
    }

    @Test
    fun `rotation 90 swaps upright dims`() {
        val w = 640
        val h = 480
        // Sensor luma: black column at sensor x<320, white at x>=320.
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val buf = ByteBuffer.allocateDirect(w * h + 2 * cw * ch).order(ByteOrder.nativeOrder())
        for (r in 0 until h) {
            for (c in 0 until w) buf.put(if (c >= 320) 235.toByte() else 16.toByte())
        }
        for (i in 0 until cw * ch) buf.put(128.toByte())
        for (i in 0 until cw * ch) buf.put(128.toByte())
        buf.position(0)
        val out = tensor()
        val lb = ScrfdPreprocess.fill(buf, w, h, 90, out)
        assertNotNull(lb)
        lb!!
        assertEquals(480, lb.uprightW)
        assertEquals(640, lb.uprightH)
        // Rotation 90 maps upright (ux,uy) -> sensor (sx=uy, sy=h-1-ux).
        // Black sensor column (sx<320) shows at upright x=... where uy<320:
        // upright (320,10) -> sx=10 -> black. White: upright (400,320) ->
        // sx=320 -> white (sensor x=320 is the first white column).
        val blackExpected = (16f - 127.5f) / 128f
        val whiteExpected = (235f - 127.5f) / 128f
        assertEquals(blackExpected, out.get(10 * 640 + 320), 0.02f) // upright (320,10)
        assertEquals(whiteExpected, out.get(320 * 640 + 400), 0.02f) // upright (400,320)
    }

    @Test
    fun `mid grey input gives mid grey tensor`() {
        val src = i420(64, 64, 128, 128, 128)
        val out = tensor()
        val lb = ScrfdPreprocess.fill(src, 64, 64, 0, out)
        assertNotNull(lb)
        val expected = (128f - 127.5f) / 128f
        for (p in intArrayOf(0, 1000, 640 * 640, 2 * 640 * 640 + 12345)) {
            assertEquals(expected, out.get(p), 1e-4f)
        }
    }

    @Test
    fun `pad regions are exactly zero`() {
        val src = i420(320, 240, 16, 128, 128)
        val out = tensor()
        ScrfdPreprocess.fill(src, 320, 240, 0, out)
        // scale=1 (240<320 -> scale=1? min(640/320=2, 640/240=2.67) = 2) ->
        // draw 640x480, padY=80. Row 0 is pad -> 0.0 everywhere.
        for (x in intArrayOf(0, 320, 639)) {
            assertEquals(0f, out.get(x), 1e-6f)
            assertEquals(0f, out.get(640 * 640 + x), 1e-6f)
            assertEquals(0f, out.get(2 * 640 * 640 + x), 1e-6f)
        }
    }

    @Test
    fun `rejects empty and too-small inputs`() {
        val out = tensor()
        assertNull(ScrfdPreprocess.fill(i420(0, 0, 0, 0, 0), 0, 0, 0, out))
        // Buffer too small for 640x480 I420.
        val tiny = ByteBuffer.allocateDirect(100)
        assertNull(ScrfdPreprocess.fill(tiny, 640, 480, 0, out))
        // Tensor too small.
        val smallOut = ByteBuffer.allocateDirect(100).asFloatBuffer()
        assertNull(ScrfdPreprocess.fill(i420(64, 64, 128, 128, 128), 64, 64, 0, smallOut))
        // compactI420: zero dims and undersized dst.
        val dst = ByteBuffer.allocateDirect(ScrfdPreprocess.i420Size(64, 64))
        val src = i420(64, 64, 128, 128, 128)
        assertTrue(ScrfdPreprocess.compactI420(src, 64, 1, src, 32, 1, src, 32, 1, 64, 64, dst))
        assertFalse(ScrfdPreprocess.compactI420(src, 64, 1, src, 32, 1, src, 32, 1, 0, 64, dst))
        val smallDst = ByteBuffer.allocateDirect(16)
        assertFalse(ScrfdPreprocess.compactI420(src, 64, 1, src, 32, 1, src, 32, 1, 64, 64, smallDst))
    }

    @Test
    fun `detection maps to normalized upright coords`() {
        // 640x480 frame, scale 1, padY 80. A detection covering the drawn
        // area's center box.
        val lb = ScrfdPreprocess.Letterbox(scale = 1f, padX = 0f, padY = 80f, uprightW = 640, uprightH = 480)
        val det = Detection(100f, 180f, 200f, 280f, 0.9f)
        val box = ScrfdPreprocess.toFaceBox(det, lb, 42L)
        assertEquals(100f / 640f, box.x1, 1e-5f)
        assertEquals((180f - 80f) / 480f, box.y1, 1e-5f)
        assertEquals(200f / 640f, box.x2, 1e-5f)
        assertEquals((280f - 80f) / 480f, box.y2, 1e-5f)
        assertEquals(0.9f, box.score, 1e-6f)
        assertEquals(42L, box.timestampMs)
        assertEquals(640, box.frameWidth)
        assertEquals(480, box.frameHeight)
    }

    @Test
    fun `toFaceBox maps content origin and far corner and clamps overhang`() {
        // Round 50 build gate: the r50 ring contract — toFaceBox output IS
        // the wire format. Geometry: 640x480 source at size 640 -> scale 1,
        // padX 0, padY 80; content occupies letterbox y in [80, 560].
        val lb = ScrfdPreprocess.Letterbox(scale = 1f, padX = 0f, padY = 80f, uprightW = 640, uprightH = 480)
        // Content origin (letterbox 0,80) -> normalized (0,0).
        val origin = ScrfdPreprocess.toFaceBox(Detection(0f, 80f, 10f, 90f, 0.5f), lb, 1L)
        assertEquals(0f, origin.x1, 1e-5f)
        assertEquals(0f, origin.y1, 1e-5f)
        // Far content corner (letterbox 640,560) -> normalized (1,1).
        val corner = ScrfdPreprocess.toFaceBox(Detection(630f, 550f, 640f, 560f, 0.5f), lb, 2L)
        assertEquals(1f, corner.x2, 1e-5f)
        assertEquals(1f, corner.y2, 1e-5f)
        // Overhang: negative x1 and y2 beyond the input size clamp to the
        // frame edges (partly out-of-frame face hugs the edge, never lands
        // off-canvas — the r49 dump failure).
        val over = ScrfdPreprocess.toFaceBox(Detection(-60f, 0f, 100f, 700f, 0.5f), lb, 3L)
        assertEquals(0f, over.x1, 1e-5f) // -60/640 = -0.09 -> clamped
        assertEquals(1f, over.y2, 1e-5f) // (700-80)/480 = 1.29 -> clamped
    }
}
