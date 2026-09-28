package com.vcamstudio.engine.aiface

import android.util.Log
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Round 49: the ONE YUV -> tensor implementation, shared by the parked
 * main-process path (FaceDetectionController.runFrame) and the live :ai
 * child path (AiDetectorBinder) — extracted verbatim from runFrame so the
 * two processes cannot drift.
 *
 * Stages:
 *  - [compactI420]: CameraX YUV_420_888 (strided/possibly interleaved
 *    chroma) -> plain packed I420 (Y | U | V, no strides) for the ring.
 *  - [fill]: packed I420 -> letterboxed 1x3x[size]x[size] CHW float tensor,
 *    (x - 127.5) / 128, rotation folded into upright->sensor sampling,
 *    padding grey 127.5 -> 0.0 after normalization.
 *  - [toFaceBox]: detection in [size] letterbox coords -> normalized
 *    upright-frame [FaceBox].
 */
object ScrfdPreprocess {

    // r55: fill() returning null used to be SILENT — the dump said RUNNING
    // for 760 frames while nothing was detected. These record WHY.
    @Volatile
    var lastNullReason: String? = null
        private set

    private val enteredLogged = AtomicBoolean(false)
    private val dimsLogged = AtomicBoolean(false)
    private val i420Logged = AtomicBoolean(false)
    private val outLogged = AtomicBoolean(false)

    // android.util.Log is not mocked on the JVM — unit tests call fill(),
    // so the diagnostics must no-op there (they always work on device).
    private fun logI(msg: String) = try { Log.i("vcam-scrfd", msg) } catch (_: Throwable) {}
    private fun logW(msg: String) = try { Log.w("vcam-scrfd", msg) } catch (_: Throwable) {}

    /** Letterbox geometry a frame was rasterized with (returned by [fill]). */
    data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val uprightW: Int,
        val uprightH: Int,
    )

    /** Minimum packed I420 bytes for w x h. */
    fun i420Size(w: Int, h: Int): Int {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        return w * h + 2 * cw * ch
    }

    /**
     * YUV_420_888 planes -> packed I420 into [dst] (position 0, limit set).
     * False on zero dims or a too-small dst; out-of-capacity chroma samples
     * read as 128 (neutral), matching the parked path's guard.
     */
    fun compactI420(
        y: ByteBuffer, yRow: Int, yPix: Int,
        u: ByteBuffer, uRow: Int, uPix: Int,
        v: ByteBuffer, vRow: Int, vPix: Int,
        w: Int, h: Int,
        dst: ByteBuffer,
    ): Boolean {
        if (w <= 0 || h <= 0) return false
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val need = i420Size(w, h)
        if (dst.capacity() < need) return false
        if (y.remaining() <= 0 || u.remaining() <= 0 || v.remaining() <= 0) return false
        dst.position(0)
        dst.limit(need)

        // Luma.
        var di = 0
        if (yPix == 1 && yRow == w) {
            y.position(0)
            for (i in 0 until w * h) dst.put(di++, y.get())
        } else {
            for (r in 0 until h) {
                for (c in 0 until w) {
                    dst.put(di++, sample(y, yRow, yPix, r, c))
                }
            }
        }
        // Chroma: U plane then V plane, packed.
        for (r in 0 until ch) {
            for (c in 0 until cw) {
                dst.put(di++, sample(u, uRow, uPix, r, c))
            }
        }
        for (r in 0 until ch) {
            for (c in 0 until cw) {
                dst.put(di++, sample(v, vRow, vPix, r, c))
            }
        }
        dst.position(0)
        return true
    }

    private fun sample(buf: ByteBuffer, rowStride: Int, pixStride: Int, r: Int, c: Int): Byte {
        val off = r * rowStride + c * pixStride
        // Out-of-range chroma reads neutral grey: 0x80 -> 128 unsigned.
        return if (off in 0 until buf.capacity()) buf.get(off) else 0x80.toByte()
    }

    /**
     * Packed I420 -> letterboxed CHW float tensor (absolute puts; [out]
     * position untouched). Returns the letterbox geometry, or null on
     * invalid dims / too-small buffer.
     */
    fun fill(
        i420: ByteBuffer,
        w: Int,
        h: Int,
        rotationDeg: Int,
        out: FloatBuffer,
        size: Int = 640,
    ): Letterbox? {
        // r55: WHICH guard fires, with the real numbers — logged once per
        // reason per run (android.util.Log: this must also work in JVM
        // tests where no Timber tree is planted).
        if (w <= 0 || h <= 0) {
            lastNullReason = "dims"
            if (dimsLogged.compareAndSet(false, true)) {
                logW("SCRFD_FILL_NULL reason=dims w=$w h=$h")
            }
            return null
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val ySize = w * h
        val cSize = cw * ch
        if (enteredLogged.compareAndSet(false, true)) {
            logI(
                "SCRFD_FILL_ENTER size=$size i420Cap=${i420.capacity()} i420Need=${ySize + 2 * cSize} " +
                    "outCapF=${out.capacity()} outNeedF=${3 * size * size}",
            )
        }
        if (i420.capacity() < ySize + 2 * cSize) {
            lastNullReason = "i420"
            if (i420Logged.compareAndSet(false, true)) {
                logW(
                    "SCRFD_FILL_NULL reason=i420 w=$w h=$h i420Cap=${i420.capacity()} i420Need=${ySize + 2 * cSize}",
                )
            }
            return null
        }
        if (out.capacity() < 3 * size * size) {
            lastNullReason = "out"
            if (outLogged.compareAndSet(false, true)) {
                logW(
                    "SCRFD_FILL_NULL reason=out size=$size outCapF=${out.capacity()} outNeedF=${3 * size * size}",
                )
            }
            return null
        }

        val uprightW = if (rotationDeg == 90 || rotationDeg == 270) h else w
        val uprightH = if (rotationDeg == 90 || rotationDeg == 270) w else h
        val scale = min(size.toFloat() / uprightW, size.toFloat() / uprightH)
        val drawW = uprightW * scale
        val drawH = uprightH * scale
        val padX = (size - drawW) / 2f
        val padY = (size - drawH) / 2f

        for (oy in 0 until size) {
            val uyF = (oy - padY) / scale
            for (ox in 0 until size) {
                val idx = oy * size + ox
                val uxF = (ox - padX) / scale
                if (uxF < 0 || uyF < 0 || uxF >= uprightW || uyF >= uprightH) {
                    out.put(idx, 0f) // pad grey 127.5 -> 0.0 after (v-127.5)/128
                    continue
                }
                // upright -> sensor (nearest)
                val ux = uxF.toInt().coerceIn(0, uprightW - 1)
                val uy = uyF.toInt().coerceIn(0, uprightH - 1)
                val sx: Int
                val sy: Int
                when (rotationDeg) {
                    90 -> { sx = uy; sy = h - 1 - ux }
                    180 -> { sx = w - 1 - ux; sy = h - 1 - uy }
                    270 -> { sx = w - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val y = (i420.get(sy * w + sx).toInt() and 0xFF)
                val uvOff = (sy shr 1) * cw + (sx shr 1)
                val u = (i420.get(ySize + uvOff).toInt() and 0xFF) - 128
                val v = (i420.get(ySize + cSize + uvOff).toInt() and 0xFF) - 128
                var r = y + 1.370705f * v
                var g = y - 0.698001f * v - 0.337633f * u
                var b = y + 1.732446f * u
                r = if (r < 0) 0f else if (r > 255f) 255f else r
                g = if (g < 0) 0f else if (g > 255f) 255f else g
                b = if (b < 0) 0f else if (b > 255f) 255f else b
                val rgb = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                val plane = size * size
                out.put(idx, ((rgb shr 16 and 0xFF) - 127.5f) / 128f)
                out.put(plane + idx, ((rgb shr 8 and 0xFF) - 127.5f) / 128f)
                out.put(2 * plane + idx, ((rgb and 0xFF) - 127.5f) / 128f)
            }
        }
        lastNullReason = null // r55: a success clears the sticky reason
        return Letterbox(scale, padX, padY, uprightW, uprightH)
    }

    /** Detection in [size] letterbox coords -> normalized upright FaceBox. */
    fun toFaceBox(detection: Detection, letterbox: Letterbox, timestampMs: Long): FaceBox {
        val x1 = ((detection.x1 - letterbox.padX) / letterbox.scale / letterbox.uprightW).coerceIn(0f, 1f)
        val y1 = ((detection.y1 - letterbox.padY) / letterbox.scale / letterbox.uprightH).coerceIn(0f, 1f)
        val x2 = ((detection.x2 - letterbox.padX) / letterbox.scale / letterbox.uprightW).coerceIn(0f, 1f)
        val y2 = ((detection.y2 - letterbox.padY) / letterbox.scale / letterbox.uprightH).coerceIn(0f, 1f)
        return FaceBox(x1, y1, x2, y2, detection.score, timestampMs, letterbox.uprightW, letterbox.uprightH)
    }
}
