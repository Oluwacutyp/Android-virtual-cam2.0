package com.vcamstudio.engine.transport

import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * r53: single-producer / multi-consumer frame ring over ONE SharedMemory
 * parcelable — the AI-ring pattern (fd over binder) with a seqlock instead
 * of slot states, because the consumer (an Xposed hook in another app's
 * process) cannot run our slot-release protocol.
 *
 * Layout (header 96 bytes, then one payload area):
 *   0  magic 'VCT0'      4 version
 *   8  format           12 width         16 height      20 rotationDeg
 *   24 timestampNs (8)   32 frameBytes    36 seq (seqlock)
 *   40 payloadBytes      44 reserved..
 *
 * Seqlock: producer writes header (seq stays EVEN), payload, then bumps
 * seq by 2 (EVEN = stable). While writing it holds seq ODD. The reader
 * copies only when seq is even and unchanged across the copy. Pure header
 * math lives in [HeaderCodec] so JVM tests can exercise it without a
 * SharedMemory.
 */
object HeaderCodec {
    const val HEADER_BYTES = 96
    const val MAGIC = 0x56435430            // 'VCT0'
    const val VERSION = 1

    fun putHeader(dst: ByteBuffer, meta: FrameMeta, frameBytes: Int, payloadBytes: Int, seq: Int) {
        dst.putInt(0, MAGIC)
        dst.putInt(4, VERSION)
        dst.putInt(8, meta.format)
        dst.putInt(12, meta.width)
        dst.putInt(16, meta.height)
        dst.putInt(20, meta.rotationDeg)
        dst.putLong(24, meta.timestampNs)
        dst.putInt(32, frameBytes)
        dst.putInt(36, seq)
        dst.putInt(40, payloadBytes)
    }

    fun magic(b: ByteBuffer): Int = b.getInt(0)
    fun seq(b: ByteBuffer): Int = b.getInt(36)
    fun payloadBytes(b: ByteBuffer): Int = b.getInt(40)
    fun meta(b: ByteBuffer): FrameMeta = FrameMeta(
        width = b.getInt(12),
        height = b.getInt(16),
        rotationDeg = b.getInt(20),
        timestampNs = b.getLong(24),
        format = b.getInt(8),
    )

    /** Seqlock read gate: stable when seq is even and unchanged between checks. */
    fun stableBefore(seq: Int): Boolean = seq % 2 == 0
}

class TransportRing private constructor(private val shared: SharedMemory) {

    private val buffer: ByteBuffer = shared.mapReadWrite().order(ByteOrder.nativeOrder())
    private var seq = 0
    private var payloadBytes = 0

    /** Producer: allocate. [width]/[height] may change per frame; capacity is fixed. */
    companion object {
        const val DEFAULT_PAYLOAD_BYTES = 96 * 96 * 96 * 3 / 2 // ~1.32 MB default guard

        fun allocate(payloadBytes: Int = DEFAULT_PAYLOAD_BYTES): TransportRing {
            val sm = SharedMemory.create("vcam-transport", HeaderCodec.HEADER_BYTES + payloadBytes)
            val r = TransportRing(sm)
            r.payloadBytes = payloadBytes
            HeaderCodec.putHeader(r.buffer, FrameMeta(0, 0, 0, 0, Formats.I420), 0, payloadBytes, 0)
            return r
        }

        /** Consumer side: map an fd received over binder. */
        fun openReadOnly(sm: SharedMemory): ByteBuffer =
            sm.mapReadOnly().order(ByteOrder.nativeOrder())
    }

    val sharedMemory: SharedMemory get() = shared

    /**
     * Publish one frame (seqlock write). [payload] is copied into the ring;
     * its position must be 0 and hold exactly frameBytes bytes.
     */
    @Synchronized
    fun writeFrame(payload: ByteBuffer, meta: FrameMeta): Boolean {
        val frameBytes = meta.width * meta.height * 3 / 2 // I420 v0
        if (frameBytes > payloadBytes) return false
        seq++ // odd: writing
        HeaderCodec.putHeader(buffer, meta, frameBytes, payloadBytes, seq)
        payload.position(0)
        payload.limit(frameBytes)
        buffer.position(HeaderCodec.HEADER_BYTES)
        buffer.put(payload)
        seq++ // even: stable
        HeaderCodec.putHeader(buffer, meta, frameBytes, payloadBytes, seq)
        return true
    }
}
