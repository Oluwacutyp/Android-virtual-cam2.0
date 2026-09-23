package com.vcamstudio.engine.render.render

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.vcamstudio.core.clock.Clock
import com.vcamstudio.core.clock.SystemClockImpl
import com.vcamstudio.core.dispatch.DefaultDispatcherProvider
import com.vcamstudio.core.dispatch.DispatcherProvider
import com.vcamstudio.engine.render.lut.Lut3D
import com.vcamstudio.engine.render.model.SceneDefinition
import com.vcamstudio.engine.render.model.TransitionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Public facade of the render engine (blueprint §B.3). Application-scoped
 * singleton: the EGL context and scene state outlive Activity recreation, so
 * a recreated preview surface re-attaches and instantly shows the last frame.
 *
 * All methods are main-thread safe; GL work happens on the engine's own thread.
 */
class RenderEngine(
    private val clock: Clock = SystemClockImpl(),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
) : RenderThread.Listener {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val collector = DiagnosticsCollector()
    private val thread = RenderThread(clock, collector)
    private val watchdog = Watchdog(
        clock,
        check = { thread.watchdogStatus() },
        onRecover = { reason -> thread.post { thread.requestOutputRecovery(reason) } },
    )

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val _health = MutableStateFlow(EngineHealth.HEALTHY)
    val health: StateFlow<EngineHealth> = _health.asStateFlow()

    /**
     * Called on the MAIN thread when a scene references an external (camera or
     * video) source for the first time. The app must feed the given surface
     * (CameraX bind / ExoPlayer setVideoSurface).
     */
    var onExternalSourceReady: ((sourceId: String, surface: Surface, width: Int, height: Int) -> Unit)? = null

    /** Called on the MAIN thread when the engine released an external source. */
    var onExternalSourceReleased: ((sourceId: String) -> Unit)? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        thread.listener = this
        thread.start()
        thread.post { thread.initEngine() }
        watchdog.start()
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                publishDiagnostics()
                // Every 10s the full VCAM-DIAG dump lands in logcat under
                // vcam-engine — tools/stress-test.sh parses exactly this.
                ticks++
                if (ticks % 10 == 0) Log.i(TAG, dump())
            }
        }
        Log.i(TAG, "RenderEngine started")
    }

    fun shutdown() {
        if (!started) return
        started = false
        watchdog.shutdown()
        thread.post { thread.shutdownGl() }
        thread.quitSafely()
        scope.cancel()
        Log.i(TAG, "RenderEngine shut down")
    }

    // ------------------------------------------------------------- commands

    /** Atomically replaces the active scene (latest wins; applied on next frame). */
    fun setScene(scene: SceneDefinition) {
        thread.pendingScene = scene
    }

    fun setTransition(spec: TransitionSpec) {
        thread.pendingTransition = spec
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        thread.post { thread.attachOutput(RenderThread.PREVIEW_OUTPUT_ID, surface, width, height) }
    }

    fun detachPreview() {
        thread.post { thread.detachOutput(RenderThread.PREVIEW_OUTPUT_ID) }
    }

    /** Registers/updates a bitmap-backed source (imported image, rasterized text). */
    fun registerBitmapSource(sourceId: String, bitmap: Bitmap) {
        thread.post { thread.registerBitmapSource(sourceId, bitmap) }
    }

    /** Closes any engine-owned source (bitmap or external). */
    fun closeSource(sourceId: String) {
        thread.post { thread.closeSource(sourceId) }
    }

    /** Manual recovery trigger (Diagnostics screen button / watchdog). */
    fun requestRecovery(reason: String) {
        thread.post { thread.requestOutputRecovery(reason) }
    }

    /** Registers/updates a named 3D LUT (applied per-layer via LayerEffects.lutId). */
    fun registerLut(name: String, lut: Lut3D) {
        thread.post { thread.registerLut(name, lut) }
    }

    fun removeLut(name: String) {
        thread.post { thread.removeLut(name) }
    }

    /** Video aspect known: resize the shared external buffer. */
    fun resizeSource(sourceId: String, width: Int, height: Int) {
        thread.post { thread.resizeSource(sourceId, width, height) }
    }

    /** Attaches the encoder input surface as a second output (recording). */
    fun attachRecordingOutput(surface: Surface, width: Int, height: Int) {
        thread.post { thread.attachOutput(RenderThread.RECORDING_OUTPUT_ID, surface, width, height) }
    }

    fun detachRecordingOutput() {
        thread.post { thread.detachOutput(RenderThread.RECORDING_OUTPUT_ID) }
    }

    // ----------------------------------------------------------- callbacks

    override fun onExternalSourceReady(sourceId: String, surface: Surface, width: Int, height: Int) {
        onExternalSourceReady?.invoke(sourceId, surface, width, height)
    }

    override fun onExternalSourceReleased(sourceId: String) {
        onExternalSourceReleased?.invoke(sourceId)
    }

    // ---------------------------------------------------------- diagnostics

    private fun publishDiagnostics() {
        val now = clock.nowMs()
        val status = thread.watchdogStatus()
        val lastActivity = maxOf(status.lastPresentMonotonicMs, status.lastRenderMonotonicMs)
        val health = when {
            collector.initError != null -> EngineHealth.UNHEALTHY
            thread.consecutiveFailedRecoveries >= 3 -> EngineHealth.UNHEALTHY
            status.expectingFrames && lastActivity > 0 &&
                now - lastActivity > 3_000 -> EngineHealth.UNHEALTHY
            collector.recentRecoveryCount(now, 10_000) > 0 -> EngineHealth.DEGRADED
            else -> EngineHealth.HEALTHY
        }
        _health.value = health
        _diagnostics.value = collector.snapshot(now, health, status.lastPresentMonotonicMs)
    }

    /** DEV DIAGNOSTIC (round 16A): render external sources as a UV gradient. */
    fun setUvDebugPass(enabled: Boolean) {
        thread.post { thread.setUvDebugPass(enabled) }
    }

    /** DEV TEST (round 16D-3): fresh VBO/VAO draw path instead of client arrays. */
    fun setVboDrawPass(enabled: Boolean) {
        thread.post { thread.setVboDrawPass(enabled) }
    }

    /** DEV TEST (round 17B): explicit GL_TRIANGLES pairs instead of TRIANGLE_STRIP. */
    fun setTrianglesOnly(enabled: Boolean) {
        thread.post { thread.setTrianglesOnly(enabled) }
    }

    /** DEV TEST (round 17C): layers render straight to the EGL surface (no scene FBO). */
    fun setDirectSurfacePass(enabled: Boolean) {
        thread.post { thread.setDirectSurfacePass(enabled) }
    }

    /** DEV BISECT (round 19): T1..T5 minimal rungs; 0 = full pipeline (T6). */
    fun setBisectLevel(level: Int) {
        thread.post { thread.setBisectLevel(level) }
    }

    /** Machine-parsable dump (used by tools/stress-test.sh and the Diagnostics UI). */
    fun dump(): String {
        val d = _diagnostics.value
        return buildString {
            appendLine("VCAM-DIAG v1")
            appendLine("health=${d.health}")
            appendLine("fps=${"%.1f".format(d.fps)}")
            appendLine("presented=${d.presentedFrames}")
            appendLine("rendered=${thread.renderedFrameCount}")
            appendLine("presentAttempts=${thread.presentAttemptCount}")
            appendLine("failedRecovers=${thread.consecutiveFailedRecoveries}")
            appendLine("outputs=${thread.outputStatus()}")
            appendLine("recent=" + thread.recentEvents().takeLast(18).joinToString(" | "))
            appendLine("dropped=${d.droppedFrames}")
            appendLine("p50ms=${"%.2f".format(d.p50Ms)}")
            appendLine("p95ms=${"%.2f".format(d.p95Ms)}")
            appendLine("maxms=${"%.2f".format(d.maxMs)}")
            appendLine("histogram=${d.histogram.joinToString(",", "[", "]")}")
            appendLine("recoveries=${d.recoveries.size}")
            appendLine("lastPresentAgeMs=${d.lastPresentAgeMs}")
            appendLine("scene=${d.sceneSize?.let { "${it.width}x${it.height}" } ?: "none"}")
            appendLine("preview=${d.previewSize?.let { "${it.width}x${it.height}" } ?: "none"}")
            appendLine("sources=${d.externalSourceCount}")
            appendLine("renderer=${d.glRenderer}")
            thread.oesDebugLine()?.let { appendLine(it) }
            thread.presentDebugLine()?.let { appendLine(it) }
            thread.drawStateLine()?.let { appendLine(it) }
            thread.presentDrawStateLine()?.let { appendLine(it) }
            thread.vboCreatedLine()?.let { appendLine(it) }
            thread.vboErrorsLine()?.let { appendLine(it) }
            thread.stagingOverflowLine()?.let { appendLine(it) }
            appendLine("bisect=${thread.bisectLevel()}")
            // (E) the EXACT shader sources compiled and last used for the
            // camera draw — verbatim, no summaries.
            thread.oesShaderSources()?.let { (vs, fs) ->
                appendLine("VERTEX_SHADER_BEGIN")
                append(vs)
                appendLine("FRAGMENT_SHADER_BEGIN")
                append(fs)
            }
            appendLine("gl=${d.glVersion}")
            appendLine("egl=${d.eglApi}")
            d.initError?.let { appendLine("initError=$it") }
            d.recoveries.forEach { appendLine("recovery=@${it.atMs} ${it.reason}") }
        }
    }

    companion object {
        private const val TAG = "vcam-engine"
    }
}
