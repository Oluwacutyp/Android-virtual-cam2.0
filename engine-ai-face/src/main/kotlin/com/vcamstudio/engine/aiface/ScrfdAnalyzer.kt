package com.vcamstudio.engine.aiface

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Round-39: the analyzer as a proper class (was a SAM lambda on
 * [FaceDetectionController]). Kotlin 2.0.20 inference hangs on the
 * nested-lambda form; the class form compiles. Behavior is identical to
 * the original lambda: 5 Hz gate, never propagates into CameraX, proxy
 * closed on every path.
 */
internal class ScrfdAnalyzer(
    private val controller: FaceDetectionController,
) : ImageAnalysis.Analyzer {

    override fun analyze(proxy: ImageProxy) {
        try {
            val d = controller.currentDetectorOrNull() ?: return
            val now = System.currentTimeMillis()
            if (now - controller.lastRunMs < 200) return
            controller.handleFrame(proxy, d, now)
        } catch (t: Throwable) {
            // Never propagate into CameraX — detection failures are logged
            // (same as the original lambda).
            Timber.w(t, "SCRFD_FRAME_FAIL")
        } finally {
            runCatching { proxy.close() }
        }
    }
}
