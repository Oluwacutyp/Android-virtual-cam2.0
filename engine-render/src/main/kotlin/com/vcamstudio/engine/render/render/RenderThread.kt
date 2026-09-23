package com.vcamstudio.engine.render.render

import android.opengl.EGL14
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
    ) {
        var firstPresentLogged: Boolean = false
        var makeCurrentWarned: Boolean = false
    }

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

    @Volatile
    var renderedFrameCount: Long = 0
        private set
    private var loopIterations = 0L

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
        try {
            val core = EglCore()
            egl = core
            programs = Shaders.buildPrograms() // throws GlException on shader bugs (fail fast)
            renderer = SceneRenderer(programs!!).also { it.enableVertexArrays() }
            collector.glRenderer = core.glRenderer
            collector.glVersion = core.glVersion
            collector.eglApi = core.eglApiVersion
            collector.initError = null
            glSmokeTest()
            initialized = true
            Log.i(TAG, "ENGINE_UP renderer=${core.glRenderer} gl=${core.glVersion}")
            scheduleFrame()
        } catch (t: Throwable) {
            // Fail VISIBLE (diagnostics + log), not silent: keep retrying so a
            // transient driver/permission condition self-heals.
            collector.initError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "ENGINE_INIT_FAILED: ${t.message} — retrying in 2s", t)
            handler.postDelayed({
                if (running && !initialized) initEngine()
            }, 2_000)
        }
    }

    /**
     * Proves the GL pipeline is alive at boot: allocates a tiny FBO, clears
     * red, reads the pixel back. Fails loudly (surfaced as initError) instead
     * of producing a silently-dead engine.
     */
    private fun glSmokeTest() {
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 4, 4, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex[0], 0,
        )
        GLES30.glClearColor(1f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val px = java.nio.ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(2, 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, tex, 0)
        val err = GLES30.glGetError()
        val red = px.get(0).toInt() and 0xFF
        if (red != 255 || err != 0) {
            throw GlException("GL_SMOKE_FAILED red=$red glErr=0x${Integer.toHexString(err)}")
        }
        Log.i(TAG, "GL_SMOKE_OK")
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
        Log.i(TAG, "SURFACE_ATTACHED id=$id ${width}x$height (${outputs.size} total)")
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

    fun registerLut(name: String, lut: com.vcamstudio.engine.render.lut.Lut3D) {
        renderer?.lutCache?.put(name, lut.size, lut.data)
    }

    fun removeLut(name: String) {
        renderer?.lutCache?.remove(name)
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
            out.makeCurrentWarned = false
            Log.i(TAG, "EGL_WINDOW_CREATED id=${out.id}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "EGL_WINDOW_FAILED id=${out.id}: ${t.message}")
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
            sceneNeedsRender = true
            Log.i(TAG, "SOURCE_CREATED $id")
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

        loopIterations++
        if (loopIterations % 300 == 0L) {
            Log.i(TAG, "FRAME_TICK loops=$loopIterations rendered=$renderedFrameCount outputs=${outputs.size}")
        }

        var dirty = false
        // One misbehaving source (abandoned surface, producer crash) must
        // never kill the whole frame — isolate it.
        for (src in allSources()) {
            val updated = runCatching { src.update() }
                .onFailure { Log.w(TAG, "SOURCE_UPDATE_FAILED ${it.message}") }
                .getOrDefault(false)
            if (updated) dirty = true
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
        // A new/edited scene must render at least once even if no source has
        // produced a frame yet — otherwise the compositor never presents.
        sceneNeedsRender = true
        Log.i(TAG, "SCENE_APPLIED ${scene.width}x${scene.height} layers=${scene.layers.size}")
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
        renderedFrameCount++
        if (renderedFrameCount == 1L) {
            Log.i(TAG, "FIRST_SCENE_RENDER ${scene.width}x${scene.height}")
        }
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

        val r = renderer
        if (r == null || scene == null) {
            // Engine not up yet (or no scene): still present the background so
            // outputs are never undefined-black and fps stays measurable.
            presentBlank(frameTimeNanos)
            return
        }
        if (presentedSceneTex == 0) {
            // Scene exists but nothing rendered yet — render once now so the
            // very first present already shows content.
            renderScene(scene)
        }

        val nowMs = clock.nowMs()
        var anySwapOk = false

        for (out in outputs.values) {
            if (out.needsReinit || out.eglSurface == null) {
                if (!reinitOutput(out)) continue
            }
            val es = out.eglSurface!!
            if (!es.makeCurrent()) {
                if (!out.makeCurrentWarned) {
                    out.makeCurrentWarned = true
                    Log.w(TAG, "MAKE_CURRENT_FAILED id=${out.id} err=${EGL14.eglGetError()}")
                }
                continue
            }
            out.makeCurrentWarned = false

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
            } else if (presentedSceneTex != 0) {
                r.drawTextureQuad(presentedSceneTex, 1f, quad, out.width, out.height)
            }

            es.setPresentationTime(frameTimeNanos)
            if (es.swap()) {
                anySwapOk = true
                checkGlError("present(${out.id})")
                if (!out.firstPresentLogged) {
                    out.firstPresentLogged = true
                    Log.i(TAG, "FIRST_PRESENT ${out.id} ${out.width}x${out.height}")
                }
            } else {
                Log.w(
                    TAG,
                    "SWAP_FAILED id=${out.id} err=0x${Integer.toHexString(EGL14.eglGetError())} — scheduling recovery",
                )
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

    /** Presents a cleared (background-colored) frame on outputs — never-black floor. */
    private fun presentBlank(frameTimeNanos: Long) {
        var anySwapOk = false
        for (out in outputs.values) {
            if (out.needsReinit || out.eglSurface == null) {
                if (!reinitOutput(out)) continue
            }
            val es = out.eglSurface!!
            if (!es.makeCurrent()) {
                if (!out.makeCurrentWarned) {
                    out.makeCurrentWarned = true
                    Log.w(TAG, "MAKE_CURRENT_FAILED id=${out.id} err=${EGL14.eglGetError()} (blank)")
                }
                continue
            }
            out.makeCurrentWarned = false
            GLES30.glViewport(0, 0, out.width, out.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            es.setPresentationTime(frameTimeNanos)
            if (es.swap()) {
                anySwapOk = true
                if (!out.firstPresentLogged) {
                    out.firstPresentLogged = true
                    Log.i(TAG, "FIRST_PRESENT ${out.id} ${out.width}x${out.height} (blank)")
                }
            } else {
                out.needsReinit = true
            }
        }
        if (anySwapOk) {
            collector.onPresented(16.7f, 0)
            lastPresentMonotonicMs = clock.nowMs()
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
        const val RECORDING_OUTPUT_ID = "recording"
        const val DEFAULT_SOURCE_W = 1280
        const val DEFAULT_SOURCE_H = 720
    }
}
