package com.vcamstudio.engine.render.render

import android.opengl.GLES30
import android.view.Choreographer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.vcamstudio.core.clock.Clock
import com.vcamstudio.core.model.Size
import com.vcamstudio.engine.render.gl.EglCore
import com.vcamstudio.engine.render.gl.EglWindowSurface
import com.vcamstudio.engine.render.gl.Framebuffer
import com.vcamstudio.engine.render.gl.GlException
import com.vcamstudio.engine.render.gl.checkGlError
import com.vcamstudio.engine.render.model.BlendMode
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.SceneDefinition
import com.vcamstudio.engine.render.model.TransitionSpec
import com.vcamstudio.engine.render.model.TransitionType
import com.vcamstudio.engine.render.shaders.Shaders
import com.vcamstudio.engine.render.source.BitmapTextureSource
import com.vcamstudio.engine.render.source.ExternalTextureSource
import com.vcamstudio.engine.render.source.TextureSource

/**
 * The single GL thread (blueprint §B.3). Owns the EGL context, all GL objects,
 * the scene FBO graph and every output surface. Everything else talks to it
 * through [post]; volatile fields expose only watchdog-readable status.
 *
 * Never-black guarantees implemented here:
 *  1. The EGL context and scene FBOs survive surface churn — attach/detach
 *     never re-creates the pipeline.
 *  2. Every vsync re-presents the last composited scene; a destroyed output
 *     degrades to frozen-last-frame, not black.
 *  3. [requestOutputRecovery] re-initializes dead surfaces in place, and the
 *     [Watchdog] calls it automatically on stalls.
 */
internal class RenderThread(
    private val clock: Clock,
    private val collector: DiagnosticsCollector,
) : HandlerThread("vcam-render") {

    /** Callbacks into the app. Invoked on the MAIN thread. */
    interface Listener {
        fun onExternalSourceReady(sourceId: String, surface: Surface, width: Int, height: Int)
        fun onExternalSourceReleased(sourceId: String)
    }

    @Volatile
    var listener: Listener? = null

    // ---- watchdog-visible volatile state
    @Volatile
    var expectsFrames: Boolean = false
        private set

    @Volatile
    var lastPresentMonotonicMs: Long = 0
        private set

    @Volatile
    var consecutiveFailedRecoveries: Int = 0
        private set

    // ---- volatile command channels (latest wins, lock-free)
    @Volatile
    var pendingScene: SceneDefinition? = null

    @Volatile
    var pendingTransition: TransitionSpec = TransitionSpec.CUT

    private val handler: Handler by lazy { Handler(looper) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- GL state (render thread only)
    private var egl: EglCore? = null
    private var programs: Shaders.Programs? = null
    private var renderer: SceneRenderer? = null
    private var initialized = false

    @Volatile
    private var running = true

    private val outputs = LinkedHashMap<String, Output>()
    private class Output(
        val id: String,
        val surface: Surface,
        var width: Int,
        var height: Int,
        var eglSurface: EglWindowSurface?,
        var needsReinit: Boolean,
    )

    // ---- scene state (render thread only)
    private var currentScene: SceneDefinition? = null
    private var transitionSpec: TransitionSpec = TransitionSpec.CUT
    private var transitionStartMs = -1L
    private var pendingTransitionCapture = false
    private var sceneNeedsRender = false

    private var sceneFboA: Framebuffer? = null
    private var sceneFboB: Framebuffer? = null
    private var prevFbo: Framebuffer? = null
    private var sceneCurIsA = true
    private var presentedSceneTex = 0
    private val fboPool = com.vcamstudio.engine.render.gl.FboPool()

    private val externalSources = LinkedHashMap<String, ExternalTextureSource>()
    private val bitmapSources = LinkedHashMap<String, BitmapTextureSource>()

    private var choreographer: Choreographer? = null
    private var lastPresentClockMs = 0L

    fun post(block: () -> Unit) {
        handler.post {
            if (!running) return@post
            try {
                block()
            } catch (t: GlException) {
                Log.e(TAG, "GL op failed on render thread", t)
                noteGlFailure(t)
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected render-thread failure", t)
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    fun initEngine() {
        if (initialized) return
        val core = EglCore()
        egl = core
        programs = Shaders.buildPrograms() // throws GlException on shader bugs (fail fast)
        renderer = SceneRenderer(programs!!).also { it.enableVertexArrays() }
        collector.glRenderer = core.glRenderer
        collector.glVersion = core.glVersion
        collector.eglApi = core.eglApiVersion
        initialized = true
        Log.i(TAG, "render engine up: ${core.glRenderer} / ${core.glVersion}")
        scheduleFrame()
    }

    fun shutdownGl() {
        running = false
        choreographer = null
        externalSources.values.forEach { runCatching { it.release() } }
        externalSources.clear()
        bitmapSources.values.forEach { runCatching { it.release() } }
        bitmapSources.clear()
        outputs.values.forEach { runCatching { it.eglSurface?.release() } }
        outputs.clear()
        sceneFboA?.release(); sceneFboB?.release(); prevFbo?.release()
        sceneFboA = null; sceneFboB = null; prevFbo = null
        fboPool.clear()
        programs?.releaseAll()
        egl?.release()
        egl = null
        programs = null
        renderer = null
        initialized = false
        Log.i(TAG, "render engine shut down")
    }

    // ----------------------------------------------------------------- outputs

    fun attachOutput(id: String, surface: Surface, width: Int, height: Int) {
        val old = outputs.remove(id)
        old?.eglSurface?.release()
        outputs[id] = Output(id, surface, width, height, eglSurface = null, needsReinit = true)
        if (id == PREVIEW_OUTPUT_ID) collector.previewSize = Size(width, height)
        Log.i(TAG, "output attached id=$id ${width}x$height (${outputs.size} total)")
    }

    fun detachOutput(id: String) {
        val out = outputs.remove(id) ?: return
        out.eglSurface?.release()
        if (id == PREVIEW_OUTPUT_ID) collector.previewSize = null
        Log.i(TAG, "output detached id=$id (${outputs.size} remain)")
        if (outputs.isEmpty() && initialized) {
            egl?.makeCurrentPbuffer() // keep context usable for source updates
        }
    }

    fun requestOutputRecovery(reason: String) {
        collector.addRecovery(RecoveryEvent(clock.nowMs(), reason))
        Log.w(TAG, "output recovery requested: $reason")
        for (out in outputs.values) out.needsReinit = true
        // Present loop performs the actual re-init on the next frame.
    }

    private fun reinitOutput(out: Output): Boolean {
        runCatching { out.eglSurface?.release() }
        out.eglSurface = null
        return try {
            out.eglSurface = EglWindowSurface(egl!!, out.surface)
            out.needsReinit = false
            true
        } catch (t: Throwable) {
            Log.e(TAG, "reinit failed for ${out.id}: ${t.message}")
            consecutiveFailedRecoveries++
            false
        }
    }

    // ------------------------------------------------------------------ sources

    fun registerBitmapSource(sourceId: String, bitmap: android.graphics.Bitmap) {
        val src = bitmapSources.getOrPut(sourceId) { BitmapTextureSource(sourceId) }
        src.setBitmap(bitmap)
    }

    fun closeSource(sourceId: String) {
        bitmapSources.remove(sourceId)?.release()
        externalSources.remove(sourceId)?.let {
            it.release()
            postMain { listener?.onExternalSourceReleased(sourceId) }
        }
    }

    private fun syncExternalSources(scene: SceneDefinition) {
        val needed = scene.externalSourceIds()
        val stale = externalSources.keys.filter { it !in needed }
        for (id in stale) {
            val src = externalSources.remove(id) ?: continue
            src.release()
            postMain { listener?.onExternalSourceReleased(id) }
        }
        for (id in needed) {
            if (externalSources.containsKey(id)) continue
            val src = ExternalTextureSource(id, DEFAULT_SOURCE_W, DEFAULT_SOURCE_H)
            externalSources[id] = src
            Log.i(TAG, "external source created: $id")
            postMain {
                listener?.onExternalSourceReady(id, src.surface, DEFAULT_SOURCE_W, DEFAULT_SOURCE_H)
            }
        }
        collector.externalSourceCount = externalSources.size
    }

    // -------------------------------------------------------------- frame loop

    private fun scheduleFrame() {
        val ch = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        ch.postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        if (!running || !initialized) return@FrameCallback
        try {
            doFrame(frameTimeNanos)
        } catch (t: GlException) {
            Log.e(TAG, "GL failure in frame loop — requesting recovery", t)
            noteGlFailure(t)
        } catch (t: Throwable) {
            Log.e(TAG, "unexpected failure in frame loop", t)
        }
        scheduleFrame()
    }

    private fun noteGlFailure(t: GlException) {
        consecutiveFailedRecoveries++
        collector.addRecovery(RecoveryEvent(clock.nowMs(), "gl failure: ${t.message}"))
    }

    private fun doFrame(frameTimeNanos: Long) {
        applyPendingScene()

        var dirty = false
        for (src in allSources()) {
            if (src.update()) dirty = true
        }

        val scene = currentScene
        if (scene != null && (dirty || sceneNeedsRender || pendingTransitionCapture || transitionActive())) {
            renderScene(scene)
            sceneNeedsRender = false
        }

        presentAll(frameTimeNanos)
    }

    private fun allSources(): Collection<TextureSource> {
        val list = ArrayList<TextureSource>(externalSources.size + bitmapSources.size)
        list.addAll(externalSources.values)
        list.addAll(bitmapSources.values)
        return list
    }

    private fun applyPendingScene() {
        val scene = pendingScene ?: return
        pendingScene = null
        val old = currentScene
        if (old != null && old.id != scene.id && transitionSpec.type == TransitionType.FADE) {
            pendingTransitionCapture = true
        }
        if (old == null || old.width != scene.width || old.height != scene.height) {
            recreateSceneFbos(scene.width, scene.height)
        }
        currentScene = scene
        collector.sceneSize = Size(scene.width, scene.height)
        transitionSpec = pendingTransition
        syncExternalSources(scene)
    }

    private fun recreateSceneFbos(w: Int, h: Int) {
        sceneFboA?.release(); sceneFboB?.release(); prevFbo?.release()
        sceneFboA = Framebuffer(w, h)
        sceneFboB = Framebuffer(w, h)
        prevFbo = Framebuffer(w, h)
        sceneCurIsA = true
        presentedSceneTex = sceneFboA!!.texture.id
        Log.i(TAG, "scene FBOs recreated ${w}x$h")
    }

    private fun renderScene(scene: SceneDefinition) {
        val r = renderer ?: return

        if (pendingTransitionCapture) {
            prevFbo?.let { r.copyFbo(currentFbo(), it) }
            transitionStartMs = clock.nowMs()
            pendingTransitionCapture = false
        }

        r.beginScene(currentFbo(), scene.backgroundArgb)

        for (layer in scene.layers) {
            if (!layer.visible || layer.opacity <= 0.01f) continue
            when (layer) {
                is LayerDefinition.Color -> {
                    r.drawColorLayer(layer, currentFbo(), scene.width, scene.height)
                }
                is LayerDefinition.Camera -> {
                    val src = externalSources[layer.id] ?: continue
                    drawTextureLayer(layer, src, scene)
                }
                is LayerDefinition.Image -> {
                    val src = bitmapSources[layer.sourceId] ?: continue
                    drawTextureLayer(layer, src, scene)
                }
                is LayerDefinition.Video -> {
                    val src = externalSources[layer.sourceId] ?: continue
                    drawTextureLayer(layer, src, scene)
                }
                is LayerDefinition.Text -> {
                    val src = bitmapSources[layer.id] ?: continue
                    drawTextureLayer(layer, src, scene)
                }
            }
        }
        r.endScene()

        presentedSceneTex = currentFbo().texture.id
    }

    private fun currentFbo(): Framebuffer = if (sceneCurIsA) sceneFboA!! else sceneFboB!!

    private fun otherFbo(): Framebuffer = if (sceneCurIsA) sceneFboB!! else sceneFboA!!

    private fun drawTextureLayer(
        layer: LayerDefinition,
        src: TextureSource,
        scene: SceneDefinition,
    ) {
        val r = renderer ?: return
        if (!src.hasContent()) return
        if (!usedComplexPath(layer)) {
            r.drawLayerDirect(layer, src, currentFbo(), scene.width, scene.height)
        } else {
            val scratch = fboPool.acquire(scene.width, scene.height)
            val blurScratch = fboPool.acquire(scene.width, scene.height)
            r.drawLayerToScratch(layer, src, scratch, blurScratch, scene.width, scene.height)
            r.blendScratchOnto(currentFbo(), otherFbo(), scratch, layer.opacity, layer.blendMode)
            fboPool.release(scratch)
            fboPool.release(blurScratch)
            sceneCurIsA = !sceneCurIsA
        }
    }

    private fun usedComplexPath(layer: LayerDefinition): Boolean =
        layer.blendMode != BlendMode.NORMAL || layer.effects.blur.isEnabled

    // ----------------------------------------------------------------- present

    private fun presentAll(frameTimeNanos: Long) {
        val scene = currentScene
        if (outputs.isEmpty()) {
            expectsFrames = false
            return
        }
        expectsFrames = true
        if (scene == null || presentedSceneTex == 0) return

        val r = renderer ?: return
        val nowMs = clock.nowMs()
        var anySwapOk = false

        for (out in outputs.values) {
            if (out.needsReinit || out.eglSurface == null) {
                if (!reinitOutput(out)) continue
            }
            val es = out.eglSurface!!
            if (!es.makeCurrent()) continue

            GLES30.glViewport(0, 0, out.width, out.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val quad = r.letterboxQuad(scene.width, scene.height, out.width, out.height)
            val t = transitionFraction(nowMs)
            if (t != null && prevFbo != null) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFuncSeparate(
                    GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                    GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                )
                r.drawTextureQuad(prevFbo!!.texture.id, 1f, quad, out.width, out.height)
                r.drawTextureQuad(presentedSceneTex, t, quad, out.width, out.height)
                GLES30.glDisable(GLES30.GL_BLEND)
            } else {
                r.drawTextureQuad(presentedSceneTex, 1f, quad, out.width, out.height)
            }

            es.setPresentationTime(frameTimeNanos)
            if (es.swap()) {
                anySwapOk = true
                checkGlError("present(${out.id})")
            } else {
                Log.w(TAG, "swapBuffers failed for ${out.id} — scheduling recovery")
                out.needsReinit = true
            }
        }

        if (anySwapOk) {
            val dt = if (lastPresentClockMs > 0) {
                (nowMs - lastPresentClockMs).toFloat().coerceIn(1f, 500f)
            } else {
                16.7f
            }
            val dropped = if (dt > 45f) ((dt / 33.3f).toInt() - 1).coerceAtLeast(1) else 0
            collector.onPresented(dt, dropped)
            lastPresentClockMs = nowMs
            lastPresentMonotonicMs = nowMs
            consecutiveFailedRecoveries = 0
        }
    }

    private fun transitionActive(): Boolean =
        transitionStartMs >= 0 && transitionSpec.type == TransitionType.FADE

    private fun transitionFraction(nowMs: Long): Float? {
        if (transitionStartMs < 0 || transitionSpec.type == TransitionType.CUT) return null
        val elapsed = nowMs - transitionStartMs
        val duration = transitionSpec.durationMs.coerceAtLeast(1L)
        if (elapsed >= duration) {
            transitionStartMs = -1L
            return null
        }
        return (elapsed.coerceAtLeast(0L)).toFloat() / duration
    }

    // ------------------------------------------------------------- utilities

    private fun postMain(block: () -> Unit) = mainHandler.post(block)

    fun watchdogStatus(): Watchdog.WatchdogStatus = Watchdog.WatchdogStatus(expectsFrames, lastPresentMonotonicMs)

    companion object {
        private const val TAG = "vcam-render"
        const val PREVIEW_OUTPUT_ID = "preview"
        const val DEFAULT_SOURCE_W = 1280
        const val DEFAULT_SOURCE_H = 720
    }
}
