package com.vcamstudio.app.ai

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.vcamstudio.engine.aiface.ScrfdPreprocess
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Round 49: the MAIN-process frame source. A class, never a SAM lambda —
 * the inline Analyzer form is what hung the Kotlin 2.0.20 compiler in r39.
 *
 * It ONLY compacts YUV_420_888 -> packed I420 (no strides for the consumer)
 * and hands the buffer to [AiProcMonitor.onFrame]. No preprocessing beyond
 * compaction, no ORT in this process. Closes the proxy on every path (the
 * r39 ring discipline).
 */
class AiFrameAnalyzer(
    private val sink: (payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long, length: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val nextFrameId = AtomicLong(0)

    /** Reused compaction scratch (single analyzer thread). */
    private var scratch: ByteBuffer? = null

    override fun analyze(proxy: ImageProxy) {
        try {
            if (proxy.format != ImageFormat.YUV_420_888) return
            val w = proxy.width
            val h = proxy.height
            if (w <= 0 || h <= 0) return
            val need = ScrfdPreprocess.i420Size(w, h)
            var buf = scratch
            if (buf == null || buf.capacity() < need) {
                buf = ByteBuffer.allocateDirect(need)
                scratch = buf
            }
            val y = proxy.planes[0].buffer.duplicate().apply { rewind() }
            val u = proxy.planes[1].buffer.duplicate().apply { rewind() }
            val v = proxy.planes[2].buffer.duplicate().apply { rewind() }
            val ok = ScrfdPreprocess.compactI420(
                y, proxy.planes[0].rowStride, proxy.planes[0].pixelStride,
                u, proxy.planes[1].rowStride, proxy.planes[1].pixelStride,
                v, proxy.planes[2].rowStride, proxy.planes[2].pixelStride,
                w, h, buf!!,
            )
            if (!ok) return
            sink(buf, w, h, proxy.imageInfo.rotationDegrees, nextFrameId.incrementAndGet(), need)
        } finally {
            runCatching { proxy.close() }
        }
    }
}
