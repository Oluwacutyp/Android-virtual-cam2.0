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
import java.nio.FloatBuffer
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
        // Round 49: ORT must NEVER be constructed in the MAIN process — the
        // session lives in the :ai child (AiInferenceService +
        // AiDetectorBinder). A non-null install here would re-arm the
        // r46/r47 native abort, so it is refused outright.
        if (modelPath != null) {
            Timber.w(
                "SCRFD_SETMODEL_REFUSED path=%s (main-process ORT is forbidden since r49 — model goes to :ai)",
                modelPath,
            )
            return
        }
        _modelPath = null
        executor.execute {
            detector?.let { runCatching { it.close() } }
            detector = null
            _phase.value = Phase.MODEL_MISSING
        }
    }

    /** NNAPI dev toggle (default off): stored only — session builds happen in :ai. */
    fun setNnapi(enabled: Boolean) {
        useNnapi = enabled
        Timber.i("SCRFD_NNAPI_STORED nnapi=%s (no main-process session build since r49)", enabled)
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
     * normalized upright-frame coordinates. Round 49: the pixel math lives
     * in [ScrfdPreprocess] — the one implementation shared with the :ai
     * child path (behavior unchanged).
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
        i420Scratch.clear()
        val ok = ScrfdPreprocess.compactI420(
            yBuf, proxy.planes[0].rowStride, proxy.planes[0].pixelStride,
            uBuf, proxy.planes[1].rowStride, proxy.planes[1].pixelStride,
            vBuf, proxy.planes[2].rowStride, proxy.planes[2].pixelStride,
            w, h, i420Scratch,
        )
        if (!ok) return null
        i420Scratch.position(0)
        val letterbox = ScrfdPreprocess.fill(i420Scratch, w, h, rotation, tensor)
            ?: return null
        val top = detector.detectTop(tensor) ?: return null
        return ScrfdPreprocess.toFaceBox(top, letterbox, System.currentTimeMillis())
    }

    /** Packed-I420 scratch for the parked local path (alloc once). */
    private val i420Scratch: ByteBuffer =
        ByteBuffer.allocateDirect(ScrfdPreprocess.i420Size(1920, 1080))
            .order(ByteOrder.nativeOrder())

    /**
     * Round 49: a result that came back from the :ai child
     * (AiDetectorClient -> AiProcMonitor.remoteResults -> VM -> here).
     * Feeds the EXISTING stats surface so the owner's dump lines
     * (SCRFD_MS / SCRFD_FPS / SCRFD_RUNS / FACE_BOX) go real. box==null
     * means the child RAN and found no face — the overlay box clears. A
     * skipped/dropped frame never reaches this.
     */
    fun reportRemoteResult(box: FaceBox?, preprocessMs: Long, inferMs: Long) {
        val now = System.currentTimeMillis()
        // Round 50: the dump's own definition of healthy — results flowing
        // means the child session is RUNNING (r49 printed SCRFD_RUNS=70
        // next to SCRFD_STATE=OFF).
        _phase.value = Phase.RUNNING
        _stats.value = _stats.value.copy(box = box)
        // r52a: FACE_KPS follows FACE_BOX — a no-face run clears both.
        if (box == null) latestKpsNorm = null
        recordRun(now, preprocessMs + inferMs)
    }

    /**
     * r52a ADDITIVE: 5 keypoints in upright PIXEL coordinates (binder
     * onKps — the boxes ring is untouched). Stored upright-normalized,
     * the same space as FACE_BOX, for the FACE_KPS dump line only.
     */
    @Volatile private var latestKpsNorm: FloatArray? = null

    fun reportRemoteKps(kps: FloatArray, uprightW: Int, uprightH: Int) {
        if (kps.size < 10 || uprightW <= 0 || uprightH <= 0) return
        val n = FloatArray(10)
        for (j in 0 until 5) {
            n[2 * j] = kps[2 * j] / uprightW
            n[2 * j + 1] = kps[2 * j + 1] / uprightH
        }
        latestKpsNorm = n
    }

    /**
     * Round 50: the child's AIDL onState (routed VM-side via
     * AiProcMonitor.childPhase) — keeps the SCRFD badge honest for the
     * non-running states too (model-missing / session-failed / idle).
     */
    fun reportRemotePhase(p: Phase) {
        _phase.value = p
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
            // r52a ADDITIVE: 5 landmarks, upright normalized (same space
            // as FACE_BOX), order Leye Reye nose Lmouth Rmouth.
            append("\nFACE_KPS=").append(
                latestKpsNorm?.joinToString(
                    ",", "[", "]",
                ) { "%.3f".format(it) } ?: "none",
            )
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
