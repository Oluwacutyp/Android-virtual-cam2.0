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
    // r54.1-X3: optional context for the first_frame breadcrumb. LEADING
    // position so the two existing trailing-lambda call sites compile.
    private val phaseCtx: android.content.Context? = null,
    private val sink: (payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long, length: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val nextFrameId = AtomicLong(0)
    private val firstFrameMarked = java.util.concurrent.atomic.AtomicBoolean(false)
    private val analyzeDrops = AtomicLong(0)

    /** Reused compaction scratch (single analyzer thread). */
    private var scratch: ByteBuffer? = null

    /**
     * r54.2-G1: ANY throw in the analyzer is a DROPPED FRAME, never app
     * death — this runs on the CameraX analyzer thread. (client.submit
     * already caught its own half; this closes the rest of the surface and
     * every future throw.) First 3 + every 100th logged.
     */
    override fun analyze(proxy: ImageProxy) {
        try {
            analyzeInner(proxy)
        } catch (t: Throwable) {
            val n = analyzeDrops.incrementAndGet()
            if (n <= 3 || n % 100 == 0L) {
                timber.log.Timber.e(t, "ANALYZE_FAIL drops=%d", n)
            }
        } finally {
            // Both finallys are runCatching-guarded; ImageProxy close is
            // idempotent, so the wrapper's backstop is safe.
            runCatching { proxy.close() }
        }
    }

    private fun analyzeInner(proxy: ImageProxy) {
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
            // r54.1-X3: first frame reached the analyzer — the pipeline is
            // delivering. Marked BEFORE the sink so a consumer crash is
            // bracketed (breadcrumb written, then the failing consumer ran).
            if (!firstFrameMarked.getAndSet(true)) {
                phaseCtx?.let { com.vcamstudio.app.crash.PhaseMark.mark(it, "first_frame") }
                // r54.2-G2a: the ACTUAL CameraX analysis resolution lands in
                // CONFIG_EFFECTIVE (dump + AI_ANALYSIS_RES line).
                if (com.vcamstudio.app.transport.DebugFlags.noteAnalysis(w, h)) {
                    timber.log.Timber.i(
                        "AI_ANALYSIS_RES=%dx%d frameBytes=%d cap=%d",
                        w, h, w * h * 3 / 2, AiDetectorClient.FRAME_PAYLOAD_BYTES,
                    )
                }
            }
            sink(buf, w, h, proxy.imageInfo.rotationDegrees, nextFrameId.incrementAndGet(), need)
            // r53: transport tap — the COPY happens inside dispatch (duplicated
            // buffer) so the AI ring is untouched and never blocked by it.
            com.vcamstudio.app.transport.TransportTap.dispatch(
                buf, w, h, proxy.imageInfo.rotationDegrees, nextFrameId.get(),
            )
        } finally {
            runCatching { proxy.close() }
        }
    }
}
