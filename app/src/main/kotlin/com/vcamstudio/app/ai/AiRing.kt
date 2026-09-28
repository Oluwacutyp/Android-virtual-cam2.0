package com.vcamstudio.app.ai

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Round 49: file-backed mmap slot ring shared between the MAIN and :ai
 * processes. One file, two MAP_SHARED mappings (same uid) — coherent through
 * the page cache. Payloads never cross binder: the per-transaction cap is
 * ~1 MB SHARED by every concurrent transaction in the process, while a
 * 640x480 I420 frame is ~460 KB and 720p headroom is ~1.4 MB.
 *
 * Layout, little endian:
 *   [0..64)              header: magic "AIRN", version 1, slotCount, slotBytes, payloadBytes
 *   [64..64+slotBytes)   slot 0: 64-byte meta (state, seq, payloadSize, tag) + payload
 *   ...                  slot N-1
 *
 * Handoff protocol: a producer fills a FREE slot's payload/tag, then
 * [publish](slot, FULL) — which bumps seq FIRST and writes state LAST (state
 * is the handoff word peers poll). A consumer reads and [release]s the slot
 * back to FREE. Every access is guarded by one lock per ring (two threads
 * touch the frame ring in main: the analyzer submit and the binder-thread
 * release).
 */
class AiRing private constructor(
    private val map: MappedByteBuffer,
    private val slotCount: Int,
    private val slotBytes: Int,
) {
    private val lock = Any()
    private val payloadBytes = slotBytes - META_BYTES

    private fun slotBase(slot: Int): Int = HEADER_BYTES + slot * slotBytes
    private fun payloadBase(slot: Int): Int = slotBase(slot) + META_BYTES

    // ---- meta ----

    fun state(slot: Int): Int = synchronized(lock) { map.getInt(slotBase(slot) + OFF_STATE) }

    fun seq(slot: Int): Int = synchronized(lock) { map.getInt(slotBase(slot) + OFF_SEQ) }

    fun payloadSize(slot: Int): Int = synchronized(lock) { map.getInt(slotBase(slot) + OFF_SIZE) }

    fun setPayloadSize(slot: Int, size: Int) {
        synchronized(lock) { map.putInt(slotBase(slot) + OFF_SIZE, size) }
    }

    fun tag(slot: Int): Long = synchronized(lock) { map.getLong(slotBase(slot) + OFF_TAG) }

    fun setTag(slot: Int, tag: Long) {
        synchronized(lock) { map.putLong(slotBase(slot) + OFF_TAG, tag) }
    }

    // ---- handoff ----

    /** Bump seq FIRST, write state LAST — state is the handoff word. */
    fun publish(slot: Int, state: Int) {
        synchronized(lock) {
            val base = slotBase(slot)
            map.putInt(base + OFF_SEQ, map.getInt(base + OFF_SEQ) + 1)
            map.putInt(base + OFF_STATE, state)
        }
    }

    fun release(slot: Int) {
        synchronized(lock) { map.putInt(slotBase(slot) + OFF_STATE, STATE_FREE) }
    }

    // ---- payload ----

    /**
     * r54.2-G2b: returns false on oversized/negative length instead of
     * throwing — the require() sat on the per-frame analyzer path and any
     * throw escaped toward the CameraX thread. The caller logs (once per
     * run) and counts via this return value; the ring itself stays free of
     * android imports so the JVM ring tests keep running.
     */
    fun writePayload(slot: Int, bytes: ByteArray, length: Int, width: Int = 0, height: Int = 0): Boolean {
        if (length < 0 || length > payloadBytes) return false
        synchronized(lock) {
            map.position(payloadBase(slot))
            map.put(bytes, 0, length)
            map.putInt(slotBase(slot) + OFF_SIZE, length)
        }
        return true
    }

    /** Returns the number of bytes copied (at most [length]). */
    fun readPayload(slot: Int, bytes: ByteArray, length: Int): Int {
        return synchronized(lock) {
            val n = minOf(length, payloadBytes, map.getInt(slotBase(slot) + OFF_SIZE))
            map.position(payloadBase(slot))
            map.get(bytes, 0, n)
            n
        }
    }

    // ---- typed payload accessors (offsets are payload-relative) ----

    fun putInt(slot: Int, offset: Int, v: Int) {
        synchronized(lock) { map.putInt(payloadBase(slot) + offset, v) }
    }

    fun getInt(slot: Int, offset: Int): Int = synchronized(lock) { map.getInt(payloadBase(slot) + offset) }

    fun putFloat(slot: Int, offset: Int, v: Float) {
        synchronized(lock) { map.putFloat(payloadBase(slot) + offset, v) }
    }

    fun getFloat(slot: Int, offset: Int): Float = synchronized(lock) { map.getFloat(payloadBase(slot) + offset) }

    fun putLong(slot: Int, offset: Int, v: Long) {
        synchronized(lock) { map.putLong(payloadBase(slot) + offset, v) }
    }

    fun getLong(slot: Int, offset: Int): Long = synchronized(lock) { map.getLong(payloadBase(slot) + offset) }

    fun close() {
        // MappedByteBuffer has no SDK unmap; dropping the reference releases
        // the mapping on GC. The lock makes concurrent access quiescent.
        synchronized(lock) { /* nothing further to flush — MAP_SHARED pages are coherent */ }
    }

    companion object {
        const val STATE_FREE = 0
        const val STATE_FULL = 1
        const val META_BYTES = 64
        const val HEADER_BYTES = 64
        private const val OFF_STATE = 0
        private const val OFF_SEQ = 4
        private const val OFF_SIZE = 8
        private const val OFF_TAG = 16
        private const val MAGIC = 0x4E524941 // "AIRN" little-endian
        private const val VERSION = 1

        /** Truncates to 0 first — never reuse a stale ring. */
        fun create(file: File, slotCount: Int, slotBytes: Int): AiRing {
            val total = totalBytes(slotCount, slotBytes)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.setLength(total)
                val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, total)
                map.putInt(0, MAGIC)
                map.putInt(4, VERSION)
                map.putInt(8, slotCount)
                map.putInt(12, slotBytes)
                map.putInt(16, slotBytes - META_BYTES)
                map.force()
                return AiRing(map, slotCount, slotBytes)
            }
        }

        /** Validates magic/version/geometry; IllegalStateException on mismatch. */
        fun open(file: File, slotCount: Int, slotBytes: Int): AiRing {
            val total = totalBytes(slotCount, slotBytes)
            val raf = RandomAccessFile(file, "rw")
            // r49 regression note: capture the length BEFORE closing — the
            // original guard built its message with raf.length() after
            // close() and raised IOException("Stream Closed") instead.
            val fileLen = raf.length()
            try {
                if (fileLen < total) {
                    throw IllegalStateException(
                        "ring too small: $file len=$fileLen need>=$total",
                    )
                }
                val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, total)
                val magic = map.getInt(0)
                val version = map.getInt(4)
                val hdrSlots = map.getInt(8)
                val hdrSlotBytes = map.getInt(12)
                if (magic != MAGIC || version != VERSION ||
                    hdrSlots != slotCount || hdrSlotBytes != slotBytes
                ) {
                    throw IllegalStateException(
                        "ring geometry mismatch: $file magic=$magic version=$version " +
                            "slots=$hdrSlots slotBytes=$hdrSlotBytes " +
                            "(expected magic=$MAGIC version=$VERSION slots=$slotCount slotBytes=$slotBytes)",
                    )
                }
                return AiRing(map, slotCount, slotBytes)
            } finally {
                raf.close()
            }
        }

        private fun totalBytes(slotCount: Int, slotBytes: Int): Long =
            HEADER_BYTES + slotCount.toLong() * slotBytes.toLong()
    }
}
