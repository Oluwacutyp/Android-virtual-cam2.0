package com.vcamstudio.engine.aiface

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phase 2 (owner mandate, DELIVERABLE 2): SCRFD live detection on the
 * camera stream.
 *
 * Threading contract (mandated):
 *  - ONE dedicated executor thread ("vcam-scrfd") runs preprocessing +
 *    ONNX inference; it is also the [ImageAnalysis.Analyzer] thread, so
 *    detection never touches the render thread. STRATEGY_KEEP_ONLY_LATEST
 *    (set app-side) drops frames while we work.
 *  - Detection is gated to 5 Hz (200 ms); between runs the previous box is
 *    held for the overlay.
 *
 * Preprocessing per frame: YUV_420_888 -> RGB sampled DIRECTLY into the
 * 1x3x640x640 CHW float tensor (nearest, letterboxed, rotation applied in
 * the coordinate mapping — one pass, alloc-once buffers, no intermediate
 * bitmaps, no readbacks; the R24 ring discipline applies).
 */
class FaceDetectionController : AutoCloseable {

    enum class Phase { OFF, MODEL_MISSING, SESSION_FAILED, RUNNING }

    data class Stats(
        val avgMs: Float = 0f,
        val fps: Float = 0f,
        val runsTotal: Long = 0L,
        val box: FaceBox? = null,
    )

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vcam-scrfd") }

    @Volatile private var detector: ScrfdDetector? = null
    @Volatile private var useNnapi: Boolean = false
    @Volatile internal var lastRunMs: Long = 0L
    @Volatile private var runsTotal: Long = 0L

    /** (tMs, durationMs) ring, last 30 runs — SCRFD_MS / SCRFD_FPS. */
    private val recent = ArrayDeque<LongArray>()

    private val tensor: FloatBuffer =
        ByteBuffer.allocateDirect(3 * 640 * 640 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /** Installs (or removes, null) the SCRFD model; safe to call anytime. */
    fun setModel(modelPath: String?) {
        _modelPath = modelPath
        executor.execute {
            detector?.let { runCatching { it.close() } }
            detector = null
            if (modelPath == null) {
                _phase.value = Phase.MODEL_MISSING
                return@execute
            }
            try {
                detector = ScrfdDetector(modelPath, useNnapi)
                _phase.value = Phase.RUNNING
            } catch (t: Throwable) {
                Timber.e(t, "ONNX_SESSION_FAIL model=%s", modelPath)
                _phase.value = Phase.SESSION_FAILED
            }
        }
    }

    /** NNAPI dev toggle (default off): applies on the next session build. */
    fun setNnapi(enabled: Boolean) {
        useNnapi = enabled
        executor.execute {
            // Recreate with the current model to apply the delegate.
            val path = _modelPath ?: return@execute
            detector?.let { runCatching { it.close() } }
            detector = null
            try {
                detector = ScrfdDetector(path, enabled)
                _phase.value = Phase.RUNNING
                Timber.i("ONNX_SESSION_REBUILT nnapi=%s", enabled)
            } catch (t: Throwable) {
                Timber.e(t, "ONNX_SESSION_FAIL nnapi=%s", enabled)
                _phase.value = Phase.SESSION_FAILED
            }
        }
    }

@Volatile private var _modelPath: String? = null

    /**
     * The analyzer to hand to [com.vcamstudio.engine.capture.CameraSource].
     * Runs on the dedicated executor; closes the proxy on every path.
     * Round-39: a proper class ([ScrfdAnalyzer]), constructed once — the
     * SAM-lambda form triggered the Kotlin 2.0.20 inference hang.
     */
    val analyzer: ImageAnalysis.Analyzer = ScrfdAnalyzer(this)

    internal fun currentDetectorOrNull(): ScrfdDetector? = detector

    /**
     * Frame body lifted verbatim from the former analyzer lambda —
     * logic unchanged, only moved (round 39).
     */
    internal fun handleFrame(proxy: ImageProxy, d: ScrfdDetector, now: Long) {
        lastRunMs = now
        val t0 = System.currentTimeMillis()
        val detection = runFrame(proxy, d)
        val t1 = System.currentTimeMillis()
        publish(detection, t1)
        recordRun(t1, t1 - t0)
    }

    /**
     * One frame: YUV -> rotated/letterboxed CHW tensor -> top detection in
     * normalized upright-frame coordinates.
     */
    private fun runFrame(proxy: ImageProxy, detector: ScrfdDetector): FaceBox? {
        if (proxy.format != ImageFormat.YUV_420_888) return null
        val w = proxy.width
        val h = proxy.height
        if (w <= 0 || h <= 0) return null // invalid dims: skip, log, continue
        val rotation = proxy.imageInfo.rotationDegrees
        val yBuf = proxy.planes[0].buffer.duplicate().apply { rewind() }
        val uBuf = proxy.planes[1].buffer.duplicate().apply { rewind() }
        val vBuf = proxy.planes[2].buffer.duplicate().apply { rewind() }
        val yRow = proxy.planes[0].rowStride
        val yPix = proxy.planes[0].pixelStride
        val uRow = proxy.planes[1].rowStride
        val uPix = proxy.planes[1].pixelStride
        val vRow = proxy.planes[2].rowStride
        val vPix = proxy.planes[2].pixelStride

        val uprightW = if (rotation == 90 || rotation == 270) h else w
        val uprightH = if (rotation == 90 || rotation == 270) w else h
        val scale = minOf(640f / uprightW, 640f / uprightH)
        val drawW = uprightW * scale
        val drawH = uprightH * scale
        val padX = (640f - drawW) / 2f
        val padY = (640f - drawH) / 2f

        tensor.rewind()
        // CHW: fill channel planes in one raster pass each. To avoid three
        // YUV decodes we first rasterize RGB into scratch arrays, then
        // deinterleave into CHW.
        val rgb = IntArray(640 * 640)
        for (oy in 0 until 640) {
            val uyF = (oy - padY) / scale
            for (ox in 0 until 640) {
                val uxF = (ox - padX) / scale
                val idx = oy * 640 + ox
                if (uxF < 0 || uyF < 0 || uxF >= uprightW || uyF >= uprightH) {
                    rgb[idx] = 0xFF7F7F7F.toInt() // pad gray 127.5
                    continue
                }
                // upright -> sensor (nearest)
                val ux = uxF.toInt().coerceIn(0, uprightW - 1)
                val uy = uyF.toInt().coerceIn(0, uprightH - 1)
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = uy; sy = h - 1 - ux }
                    180 -> { sx = w - 1 - ux; sy = h - 1 - uy }
                    270 -> { sx = w - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val y = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF)
                val uvOff = (sy shr 1) * uRow + (sx shr 1) * uPix
                val u = (if (uvOff < uBuf.capacity()) uBuf.get(uvOff).toInt() and 0xFF else 128) - 128
                val vv = (if (uvOff < vBuf.capacity()) vBuf.get(uvOff).toInt() and 0xFF else 128) - 128
                var r = y + 1.370705f * vv
                var g = y - 0.698001f * vv - 0.337633f * u
                var b = y + 1.732446f * u
                r = if (r < 0) 0f else if (r > 255f) 255f else r
                g = if (g < 0) 0f else if (g > 255f) 255f else g
                b = if (b < 0) 0f else if (b > 255f) 255f else b
                rgb[idx] = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        val plane = 640 * 640
        for (i in 0 until plane) {
            val px = rgb[i]
            tensor.put(i, ((px shr 16 and 0xFF) - 127.5f) / 128f)
            tensor.put(plane + i, ((px shr 8 and 0xFF) - 127.5f) / 128f)
            tensor.put(2 * plane + i, ((px and 0xFF) - 127.5f) / 128f)
        }

        val top = detector.detectTop(tensor) ?: return null
        // 640 letterbox coords -> normalized upright frame.
        val x1 = ((top.x1 - padX) / scale / uprightW).coerceIn(0f, 1f)
        val y1 = ((top.y1 - padY) / scale / uprightH).coerceIn(0f, 1f)
        val x2 = ((top.x2 - padX) / scale / uprightW).coerceIn(0f, 1f)
        val y2 = ((top.y2 - padY) / scale / uprightH).coerceIn(0f, 1f)
        return FaceBox(x1, y1, x2, y2, top.score, System.currentTimeMillis(), uprightW, uprightH)
    }

    private fun publish(box: FaceBox?, now: Long) {
        if (box != null) {
            _stats.value = _stats.value.copy(box = box)
        }
    }

    private fun recordRun(tMs: Long, durationMs: Long) {
        synchronized(recent) {
            recent.addLast(longArrayOf(tMs, durationMs))
            while (recent.size > 30) recent.removeFirst()
            runsTotal++
            var sum = 0L
            var fpsCount = 0
            for (e in recent) {
                sum += e[1]
                if (tMs - e[0] <= 1_000) fpsCount++
            }
            val avg = sum.toFloat() / recent.size
            _stats.value = _stats.value.copy(avgMs = avg, fps = fpsCount.toFloat(), runsTotal = runsTotal)
        }
    }

    /** Diagnostics/dump section (LAUNCH LOG convention keeps engine dump first). */
    fun dumpSection(): String {
        val s = _stats.value
        val boxLine = s.box?.let { "[%.3f,%.3f,%.3f,%.3f]".format(it.x1, it.y1, it.x2, it.y2) } ?: "none"
        return buildString {
            append("SCRFD_MS=%.2f".format(s.avgMs))
            append("\nSCRFD_FPS=%.1f".format(s.fps))
            append("\nSCRFD_RUNS=").append(s.runsTotal)
            append("\nSCRFD_STATE=").append(_phase.value)
            append("\nSCRFD_NNAPI=").append(useNnapi)
            append("\nFACE_BOX=").append(boxLine)
            s.box?.let { append(" score=%.3f".format(it.score)) }
        }
    }

    override fun close() {
        runCatching {
            executor.execute {
                detector?.let { runCatching { it.close() } }
                detector = null
                _phase.value = Phase.OFF
            }
        }
        executor.shutdown()
    }
}
