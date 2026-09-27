package com.vcamstudio.app.ai

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round 49: the cross-process ring contract. The key property under test is
 * the r49 IPC premise: TWO independent mappings of ONE file — a producer
 * mapping sees what a consumer mapping wrote, exactly like the main/:ai
 * pair in production.
 */
class AiRingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun geometry(slotCount: Int, payloadBytes: Int) =
        slotCount to (AiRing.META_BYTES + payloadBytes)

    @Test
    fun `payload survives across two mappings of one file`() {
        val f = File(tmp.root, "frames.ring")
        val (slots, slotBytes) = geometry(2, 1024)
        val producer = AiRing.create(f, slots, slotBytes)
        val payload = ByteArray(1024) { (it % 251).toByte() }
        producer.writePayload(0, payload, payload.size)
        producer.setTag(0, 4242L)
        producer.publish(0, AiRing.STATE_FULL)

        // Second mapping, as the :ai child would open it.
        val consumer = AiRing.open(f, slots, slotBytes)
        assertEquals(AiRing.STATE_FULL, consumer.state(0))
        assertEquals(4242L, consumer.tag(0))
        assertEquals(payload.size, consumer.payloadSize(0))
        val out = ByteArray(payload.size)
        assertEquals(payload.size, consumer.readPayload(0, out, out.size))
        assertArrayEquals(payload, out)

        // Consumer releases; producer's mapping sees FREE.
        consumer.release(0)
        assertEquals(AiRing.STATE_FREE, producer.state(0))
        producer.close()
        consumer.close()
    }

    @Test
    fun `box fields round-trip through second mapping`() {
        val f = File(tmp.root, "boxes.ring")
        val producer = AiRing.create(f, AiDetectorClient.BOX_SLOTS, AiDetectorClient.BOX_SLOT_BYTES)
        val consumer = AiRing.open(f, AiDetectorClient.BOX_SLOTS, AiDetectorClient.BOX_SLOT_BYTES)

        producer.putInt(3, AiDetectorClient.BOX_HAS_FACE, 1)
        producer.putFloat(3, AiDetectorClient.BOX_X1, 0.10f)
        producer.putFloat(3, AiDetectorClient.BOX_Y1, 0.20f)
        producer.putFloat(3, AiDetectorClient.BOX_X2, 0.30f)
        producer.putFloat(3, AiDetectorClient.BOX_Y2, 0.40f)
        producer.putFloat(3, AiDetectorClient.BOX_SCORE, 0.95f)
        producer.putLong(3, AiDetectorClient.BOX_TS, 123456789L)
        producer.putInt(3, AiDetectorClient.BOX_FRAME_W, 1280)
        producer.putInt(3, AiDetectorClient.BOX_FRAME_H, 720)
        producer.publish(3, AiRing.STATE_FULL)

        assertEquals(1, consumer.getInt(3, AiDetectorClient.BOX_HAS_FACE))
        assertEquals(0.10f, consumer.getFloat(3, AiDetectorClient.BOX_X1), 1e-6f)
        assertEquals(0.20f, consumer.getFloat(3, AiDetectorClient.BOX_Y1), 1e-6f)
        assertEquals(0.30f, consumer.getFloat(3, AiDetectorClient.BOX_X2), 1e-6f)
        assertEquals(0.40f, consumer.getFloat(3, AiDetectorClient.BOX_Y2), 1e-6f)
        assertEquals(0.95f, consumer.getFloat(3, AiDetectorClient.BOX_SCORE), 1e-6f)
        assertEquals(123456789L, consumer.getLong(3, AiDetectorClient.BOX_TS))
        assertEquals(1280, consumer.getInt(3, AiDetectorClient.BOX_FRAME_W))
        assertEquals(720, consumer.getInt(3, AiDetectorClient.BOX_FRAME_H))
        producer.close()
        consumer.close()
    }

    @Test
    fun `seq bumps on every publish and tag travels`() {
        val f = File(tmp.root, "seq.ring")
        val (slots, slotBytes) = geometry(1, 16)
        val r1 = AiRing.create(f, slots, slotBytes)
        val r2 = AiRing.open(f, slots, slotBytes)
        assertEquals(0, r1.seq(0))
        r1.setTag(0, 7L)
        r1.publish(0, AiRing.STATE_FULL)
        r1.publish(0, AiRing.STATE_FULL)
        assertEquals(2, r2.seq(0))
        assertEquals(7L, r2.tag(0))
        // release() does NOT bump seq — only publish does.
        r2.release(0)
        assertEquals(2, r1.seq(0))
        r1.close()
        r2.close()
    }

    @Test
    fun `720p I420 frame fits one frame slot`() {
        val f = File(tmp.root, "frames720.ring")
        val frameBytes = 1_382_400 // 1280*720*3/2
        assertEquals(frameBytes, 1280 * 720 * 3 / 2)
        val producer = AiRing.create(f, AiDetectorClient.FRAME_SLOTS, AiDetectorClient.FRAME_SLOT_BYTES)
        val consumer = AiRing.open(f, AiDetectorClient.FRAME_SLOTS, AiDetectorClient.FRAME_SLOT_BYTES)
        val frame = ByteArray(frameBytes) { (it % 7).toByte() }
        producer.writePayload(1, frame, frame.size)
        producer.publish(1, AiRing.STATE_FULL)
        val out = ByteArray(frameBytes)
        assertEquals(frameBytes, consumer.readPayload(1, out, out.size))
        assertArrayEquals(frame, out)
        producer.close()
        consumer.close()
    }

    @Test
    fun `create truncates stale content - never reused`() {
        val f = File(tmp.root, "stale.ring")
        val (slots, slotBytes) = geometry(2, 64)
        val r1 = AiRing.create(f, slots, slotBytes)
        r1.writeInts(0)
        r1.publish(0, AiRing.STATE_FULL)
        r1.close()
        // Recreate: everything back to pristine (state FREE, seq 0).
        val r2 = AiRing.create(f, slots, slotBytes)
        assertEquals(AiRing.STATE_FREE, r2.state(0))
        assertEquals(0, r2.seq(0))
        r2.close()
    }

    @Test
    fun `open rejects geometry mismatch with IllegalStateException`() {
        val f = File(tmp.root, "mismatch.ring")
        val good = AiRing.create(f, 2, AiRing.META_BYTES + 64)
        good.close()
        try {
            AiRing.open(f, 4, AiRing.META_BYTES + 64)
            fail("expected IllegalStateException for slotCount mismatch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
        try {
            AiRing.open(f, 2, AiRing.META_BYTES + 128)
            fail("expected IllegalStateException for slotBytes mismatch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
    }

    @Test
    fun `open rejects too-small file with IllegalStateException not IOException`() {
        val f = File(tmp.root, "small.ring")
        f.writeBytes(ByteArray(10))
        try {
            AiRing.open(f, 2, AiRing.META_BYTES + 1024)
            fail("expected IllegalStateException for undersized ring file")
        } catch (e: IllegalStateException) {
            // r49 regression guard: the message once read raf.length() AFTER
            // close() -> IOException("Stream Closed") masked this branch.
            assertTrue(e.message!!.contains("too small"))
        }
    }

    private fun AiRing.writeInts(slot: Int) {
        putInt(slot, 0, 1)
        putInt(slot, 4, 2)
    }
}
