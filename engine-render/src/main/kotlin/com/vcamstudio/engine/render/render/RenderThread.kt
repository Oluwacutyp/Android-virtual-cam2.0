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

    /** Latest 1 Hz OES orientation capture (ST matrix + UVs) for the dump. */
    fun oesDebugLine(): String? = renderer?.oesDebug

    /** Exact shader sources of the program last used for the camera draw. */
    fun oesShaderSources(): Pair<String, String>? = renderer?.lastOesProgramSources

    /** 1 Hz present-pass clip capture (round-16B wedge signature). */
    fun presentDebugLine(): String? = renderer?.presentDebug

    /** 1 Hz draw-state audits (round-16C): camera-layer + present pass. */
    /** Round 22 mandate 5: DRAW_PATH marker line for the dump. */
    fun drawPathLine(): String? =
        renderer?.let {
            "DRAW_PATH=${SceneRenderer.DRAW_PATH}" + (it.drawPathViolation?.let { v -> " $v" } ?: "")
        }

    fun drawStateLine(): String? = renderer?.drawStateDebug

    fun presentDrawStateLine(): String? = renderer?.presentDrawStateDebug

    /** DEV DIAGNOSTIC (round 16A): toggle the UV-gradient pass for OES layers. */
    fun setUvDebugPass(enabled: Boolean) {
        renderer?.uvDebugPass = enabled
    }

    /** DEV TEST (round 16D-3): draw quads through a fresh VBO/VAO instead of client arrays. */
    fun setVboDrawPass(enabled: Boolean) {
        renderer?.vboDrawPass = enabled
    }

    /** DEV TEST (round 17B): explicit GL_TRIANGLES pairs instead of TRIANGLE_STRIP. */
    // Round 22: setTrianglesOnly DELETED — the round-17 toggle is gone with
    // the strip path; TRIANGLES is the only draw path (SceneRenderer.DRAW_PATH,
    // asserted at boot in initEngine).

    /** DEV TEST (round 17C): render layers straight to the EGL surface, no scene FBO. */
    fun setDirectSurfacePass(enabled: Boolean) {
        renderer?.directSurfacePass = enabled
    }

    /** DEV BISECT (round 19): T1..T5 minimal-rung renderer; 0 = normal pipeline. */
    fun setBisectLevel(level: Int) {
        renderer?.bisectLevel = level
    }

    fun vboErrorsLine(): String? = renderer?.vboErrors

    /** STAGING_OVERFLOW last occurrence (round-18 guard). */
    fun stagingOverflowLine(): String? = renderer?.stagingOverflow

    fun bisectLevel(): Int = renderer?.bisectLevel ?: 0

    fun vboCreatedLine(): String? = renderer?.vboCreatedNote
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
        var makeCurrentFailures: Int = 0
        var swapFailures: Int = 0
        var createFailures: Int = 0
        var lastCreateAttemptMs: Long = 0L
        var gaveUp: Boolean = false
        /** Render counter at last successful swap (present-on-change gate). */
        var lastPresentedRender: Long = -1L

        /** Round 24: WHY this output needs a new EGL window surface (the
         *  reason= field on the next EGL_WINDOW_CREATED line). */
        var lastReinitReason: String = "attach"
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

    /** Swap attempts that reached the driver (dump: attempted vs succeeded). */
    @Volatile
    var presentAttemptCount: Long = 0
        private set

    /** Monotonic ts of the last renderScene — liveness signal for the watchdog. */
    @Volatile
    var lastRenderMonotonicMs: Long = 0
        private set

    /** Last on-screen letterbox quad (dump: catches geometric wedges). */
    @Volatile
    var lastPresentQuad: FloatArray = FloatArray(0)
        private set
    private var loopIterations = 0L

    /** Ring of recent milestone lines — embedded in dump() so a "Copy dump"
     *  report is decisive even without logcat. */
    private val eventLog = ArrayDeque<String>(48)
    private val eventLock = Any()

    // ---- round 24 mandate 1: per-frame ring buffers (300 = 10s @ 30fps) ----
    // Single writer = render thread (lock-free, write index only); the dump
    // snapshots slots oldest->newest, torn/blank entries are skipped.
    private val frameRing = FrameRing(300)
    private val probeRing = FrameRing(300)

    fun frameRingLines(): List<String> = frameRing.snapshot()
    fun probeRingLines(): List<String> = probeRing.snapshot()

    // per-frame accumulators (render thread only, reset at doFrame start)
    private var frLayerDraws = 0
    private var frCamReady = false
    private var frVidReady = false
    private var frSwapSkipped = false
    private var frSwapMs = 0L
    private var frRenderedAtStart = 0L

    fun noteEvent(line: String) {
        val stamped = "${clock.nowMs()} $line"
        synchronized(eventLock) {
            if (eventLog.size >= 40) eventLog.removeFirst()
            eventLog.addLast(stamped)
        }
        Log.i(TAG, line)
    }

    fun recentEvents(): List<String> = synchronized(eventLog) { eventLog.toList() }

    /** Round-20 MANDATE 2: append-only boot/launch record (never trimmed by
     *  steady-state events) — EGL_CTX, EGL_WINDOW_CREATED, SURFACE_ATTACHED,
     *  SURFACE_RESIZED, SURFACE_DETACHED, FIRST_PRESENT, SHADER_COMPILED,
     *  GL_ERROR. Prepended to every dump as the LAUNCH LOG block. */
    private val launchLog = ArrayDeque<String>(96)
    private val launchLock = Any()

    fun noteLaunch(line: String) {
        val stamped = "[t=${clock.nowMs()}] $line"
        synchronized(launchLock) {
            if (launchLog.size >= 96) launchLog.removeFirst()
            launchLog.addLast(stamped)
        }
        Log.i(TAG, "LAUNCH $line")
    }

    fun launchLogLines(): List<String> = synchronized(launchLock) { launchLog.toList() }

    /** Last swap duration in ms — MANDATE 3 stall attribution input. */
    @Volatile
    var lastSwapDurationMs: Long = 0
        private set

    /**
     * Round-20 MANDATE 3: every watchdog stall entry must record WHICH thread
     * stalled, WHAT call it was blocked in, and what the render loop did in
     * the moments before. Runs on the watchdog thread — it only READS state,
     * and captures the render thread's stack (deepest frames first) so a
     * native block (eglSwapBuffers, lock, queue wait) is visible verbatim.
     */
    fun stallDiagnostics(reason: String): String {
        val frames = Thread.getAllStackTraces()[this]
            ?.take(8)
            ?.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            ?.joinToString(" <- ")
            ?: "no-stack"
        val prior = synchronized(eventLog) { eventLog.takeLast(3).joinToString(" ; ") }
        return "$reason thread=${name} swapMs=$lastSwapDurationMs renderStack=[$frames] prior=[$prior]"
    }

    fun outputStatus(): String =
        outputs.entries.joinToString(";") { (id, o) ->
            "$id:egl=${o.eglSurface != null},mcFail=${o.makeCurrentFailures}," +
                "swapFail=${o.swapFailures},valid=${o.surface.isValid},${o.width}x${o.height}"
        }

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
            noteLaunch(
                "EGL_CTX vendor=${GLES30.glGetString(GLES30.GL_VENDOR)} " +
                    "renderer=${core.glRenderer} version=${core.glVersion} egl=${core.eglApiVersion}",
            )
            programs = Shaders.buildPrograms() // throws GlException on shader bugs (fail fast)
            // MANDATE 2: exact SHADER_COMPILED lines (handle + driver info log) at boot.
            listOf(
                "tex2d" to programs!!.tex2d, "texOes" to programs!!.texOes,
                "tex2dLut" to programs!!.tex2dLut, "texOesLut" to programs!!.texOesLut,
                "fill" to programs!!.fill, "blur" to programs!!.blur,
                "blend" to programs!!.blend, "copy" to programs!!.copy,
                "uvDebug" to programs!!.uvDebug,
                "bisectSolid" to programs!!.bisectSolid, "bisectSolidAttr" to programs!!.bisectSolidAttr,
            ).forEach { (name, p) ->
                noteLaunch("SHADER_COMPILED prog=${p.handle} name=$name logs=${p.compileLog.ifEmpty { "-" }}")
            }
            renderer = SceneRenderer(programs!!).also { it.enableVertexArrays() }
            // Round 22 mandate 5: permanent draw-path assert at boot.
            check(SceneRenderer.DRAW_PATH == "TRIANGLES") {
                "DRAW_PATH regression: expected TRIANGLES, got ${SceneRenderer.DRAW_PATH}"
            }
            noteLaunch("DRAW_PATH=TRIANGLES assert=ok")
            collector.glRenderer = core.glRenderer
            collector.glVersion = core.glVersion
            collector.eglApi = core.eglApiVersion
            collector.initError = null
            glSmokeTest()
            initialized = true
            noteEvent("ENGINE_UP ${core.glRenderer}")
            scheduleFrame()
        } catch (t: Throwable) {
            // Fail VISIBLE (diagnostics + log), not silent: keep retrying so a
            // transient driver/permission condition self-heals.
            collector.initError = t.message ?: t.javaClass.simpleName
            noteEvent("ENGINE_INIT_FAILED ${t.message}")
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
        noteEvent("GL_SMOKE_OK")
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
        val old = outputs[id]
        if (old != null && old.surface === surface && surface.isValid && old.eglSurface != null) {
            // Same live surface re-attached (view resize): keep the EGL window
            // surface — only the size changed. No destroy/recreate churn.
            val fromW = old.width
            val fromH = old.height
            old.width = width
            old.height = height
            if (id == PREVIEW_OUTPUT_ID) collector.previewSize = Size(width, height)
            noteEvent("SURFACE_RESIZED id=$id ${width}x$height (egl surface kept)")
            noteLaunch("SURFACE_RESIZED from=${fromW}x$fromH to=${width}x$height id=$id")
            return
        }
        outputs.remove(id)
        old?.let { o ->
            o.eglSurface?.release() // previous EGL window surface released BEFORE recreate
            noteEvent("EGL_WINDOW_DESTROYED dims=${o.width}x${o.height} reason=replace-on-attach id=$id")
            noteLaunch("EGL_WINDOW_DESTROYED dims=${o.width}x${o.height} reason=replace-on-attach id=$id")
        }
        val out = Output(id, surface, width, height, eglSurface = null, needsReinit = true)
        out.createFailures = 0
        out.gaveUp = false // a fresh attach re-opens the attempt latch
        out.lastReinitReason = "attach"
        outputs[id] = out
        if (id == PREVIEW_OUTPUT_ID) collector.previewSize = Size(width, height)
        noteEvent("SURFACE_ATTACHED id=$id ${width}x$height valid=${surface.isValid}")
        noteLaunch("SURFACE_ATTACHED dims=${width}x$height id=$id")
    }

    fun detachOutput(id: String) {
        val out = outputs.remove(id) ?: return
        out.eglSurface?.release()
        if (id == PREVIEW_OUTPUT_ID) collector.previewSize = null
        Log.i(TAG, "output detached id=$id (${outputs.size} remain)")
        noteEvent("EGL_WINDOW_DESTROYED dims=${out.width}x${out.height} reason=detach id=$id")
        noteLaunch("SURFACE_DETACHED id=$id")
        noteLaunch("EGL_WINDOW_DESTROYED dims=${out.width}x${out.height} reason=detach id=$id")
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

    /** Producer aspect known (video size): resize the shared buffer. */
    fun resizeSource(sourceId: String, width: Int, height: Int) {
        val src = externalSources[sourceId] ?: return
        if (src.width == width && src.height == height) return
        runCatching { src.surfaceTexture.setDefaultBufferSize(width, height) }
        src.setSize(width, height)
        sceneNeedsRender = true
        noteEvent("SOURCE_RESIZED $sourceId ${width}x$height")
    }

    fun requestOutputRecovery(reason: String) {
        collector.addRecovery(RecoveryEvent(clock.nowMs(), reason))
        Log.w(TAG, "output recovery requested: $reason")
        for (out in outputs.values) {
            out.needsReinit = true
            out.lastReinitReason = "recovery:$reason"
        }
        // Present loop performs the actual re-init on the next frame.
    }

    private fun reinitOutput(out: Output): Boolean {
        // Give-up latch: after repeated create failures we STOP retrying (the
        // round-6 Camon run spammed EGL_BAD_ALLOC 802 times at ~30ms). A fresh
        // attachOutput (surface re-created by the view) re-opens attempts, and
        // the RAW preview path remains usable meanwhile.
        if (out.gaveUp) return false
        // Backoff: at most one eglCreateWindowSurface attempt per 500ms.
        val now = clock.nowMs()
        if (now - out.lastCreateAttemptMs < 500) return false
        // Never hand EGL an invalid or zero-sized surface.
        if (!out.surface.isValid || out.width <= 0 || out.height <= 0) {
            noteEvent("EGL_WINDOW_SKIPPED id=${out.id} valid=${out.surface.isValid} ${out.width}x${out.height}")
            return false
        }
        out.lastCreateAttemptMs = now
        runCatching { out.eglSurface?.release() }
        out.eglSurface = null
        return try {
            out.eglSurface = EglWindowSurface(egl!!, out.surface)
            out.needsReinit = false
            out.makeCurrentFailures = 0
            out.swapFailures = 0
            out.createFailures = 0
            noteEvent("EGL_WINDOW_CREATED id=${out.id}")
            val preserved = out.eglSurface?.swapBehaviorPreserved()
            noteLaunch(
                "EGL_WINDOW_CREATED dims=${out.width}x${out.height} id=${out.id} " +
                    "reason=${out.lastReinitReason} swapPreserved=$preserved",
            )
            true
        } catch (t: Throwable) {
            out.createFailures++
            if (out.createFailures >= 3) {
                out.gaveUp = true
                out.needsReinit = false
                noteEvent(
                    "OUTPUT_GAVE_UP id=${out.id} after ${out.createFailures} create failures (${t.message}); RAW preview remains available",
                )
            } else {
                noteEvent("EGL_WINDOW_FAILED id=${out.id} n=${out.createFailures}: ${t.message}")
            }
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
            noteEvent("SOURCE_CREATED $id")
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
            noteEvent("FRAME_ERROR GlException: ${t.message}")
            noteGlFailure(t)
        } catch (t: Throwable) {
            noteEvent("FRAME_ERROR ${t.javaClass.simpleName}: ${t.message}")
        }
        scheduleFrame()
    }

    private fun noteGlFailure(t: GlException) {
        consecutiveFailedRecoveries++
        collector.addRecovery(RecoveryEvent(clock.nowMs(), "gl failure: ${t.message}"))
        noteLaunch("GL_ERROR code=${t.message} at frame-loop")
    }

    private fun doFrame(frameTimeNanos: Long) {
        applyPendingScene()
        frLayerDraws = 0; frCamReady = false; frVidReady = false
        frSwapSkipped = false; frSwapMs = 0L
        frRenderedAtStart = renderedFrameCount

        loopIterations++
        if (loopIterations % 60 == 0L) {
            val mcFails = outputs.values.sumOf { it.makeCurrentFailures }
            val swapFails = outputs.values.sumOf { it.swapFailures }
            val allValid = outputs.values.all { it.surface.isValid }
            Log.i(
                TAG,
                "FRAME_TICK loops=$loopIterations rendered=$renderedFrameCount outputs=${outputs.size} " +
                    "makeCurrentFails=$mcFails swapFails=$swapFails surfacesValid=$allValid",
            )
        }

        var dirty = false
        // One misbehaving source (abandoned surface, producer crash) must
        // never kill the whole frame — isolate it.
        for (src in allSources()) {
            val updated = runCatching { src.update() }
                .onFailure { Log.w(TAG, "SOURCE_UPDATE_FAILED ${it.message}") }
                .getOrDefault(false)
            if (updated) {
                dirty = true
                // Round 24 mandate 1: per-source frame readiness (camera vs video).
                if (src is com.vcamstudio.engine.render.source.ExternalTextureSource) {
                    if (src.sourceId.contains("cam", ignoreCase = true)) frCamReady = true else frVidReady = true
                }
            }
        }

        val scene = currentScene
        if (scene != null && (dirty || sceneNeedsRender || pendingTransitionCapture || transitionActive())) {
            runCatching { renderScene(scene) }
                .onFailure {
                    noteEvent("RENDER_CRASH ${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.firstOrNull()}")
                }
            sceneNeedsRender = false
        }

        runCatching { presentAll(frameTimeNanos) }.onFailure { t ->
            noteEvent("PRESENT_CRASH ${t.javaClass.simpleName}: ${t.message} @ ${t.stackTrace.firstOrNull()}")
        }
        appendFrameEntry()
    }

    /** Round 24 mandate 1: one lock-free ring line per frame. */
    private fun appendFrameEntry() {
        val nowMs = clock.nowMs()
        val st = externalSources.values.firstOrNull {
            it.sourceId.contains("cam", ignoreCase = true)
        } ?: externalSources.values.firstOrNull()
        val stHash = if (st == null) "none" else stHashOf(st.transformMatrix)
        val surfaceId = (outputs[PREVIEW_OUTPUT_ID] ?: outputs.values.firstOrNull())?.id ?: "none"
        val sinceLast = if (lastPresentClockMs > 0) nowMs - lastPresentClockMs else -1L
        frameRing.append(
            "FRAME idx=$loopIterations t=$nowMs layer_draws=$frLayerDraws " +
                "cam_frame_ready=$frCamReady vid_frame_ready=$frVidReady " +
                "scene_fbo_written=${renderedFrameCount > frRenderedAtStart} " +
                "present_swap_ms=$frSwapMs since_last_present_ms=$sinceLast " +
                "st_hash=$stHash surface_id=$surfaceId" +
                (if (frSwapSkipped) " swap_skipped=unchanged" else ""),
        )
    }

    /**
     * Round 24 mandate 2: 1x1 center readback of the preview surface.
     * PRE reads the completed back buffer RIGHT BEFORE the swap — that is
     * the content about to be presented (ground truth). POST reads after
     * eglSwapBuffers returns, exactly as mandated; on non-preserved swap
     * behavior that buffer is the recycled back buffer (undefined content),
     * which is why BOTH lines exist and swapBehaviorPreserved is logged at
     * window creation.
     */
    private fun presentProbe(out: Output, pre: Boolean) {
        val px = java.nio.ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(out.width / 2, out.height / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        val r = px.get(0).toInt() and 0xFF
        val g = px.get(1).toInt() and 0xFF
        val b = px.get(2).toInt() and 0xFF
        probeRing.append(
            (if (pre) "PRESENT_PROBE_PRE" else "PRESENT_PROBE") +
                " idx=$loopIterations rgb=$r,$g,$b swapMs=$lastSwapDurationMs",
        )
    }

    private fun stHashOf(m: FloatArray): String {
        var h = 0x811C9DC5L
        for (f in m) {
            h = h xor (java.lang.Float.floatToIntBits(f).toLong() and 0xFFFFFFFFL)
            h *= 0x01000193L
            h = h and 0xFFFFFFFFL
        }
        return String.format("%08X", h)
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
        if (scene == currentScene) {
            // Identical scene re-commit (UI burst/spam): no-op. A changed
            // transition still lands.
            if (transitionSpec != pendingTransition) {
                transitionSpec = pendingTransition
                sceneNeedsRender = true
            }
            return
        }
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
        noteEvent("SCENE_APPLIED ${scene.width}x${scene.height} layers=${scene.layers.size}")
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

        var drawn = 0
        var noContent = 0
        var noSource = 0
        for (layer in scene.layers) {
            if (!layer.visible || layer.opacity <= 0.01f) continue
            when (layer) {
                is LayerDefinition.Color -> {
                    r.drawColorLayer(layer, currentFbo(), scene.width, scene.height)
                    drawn++
                }
                is LayerDefinition.Camera -> {
                    val src = externalSources[layer.id]
                    if (src == null) noSource++
                    else if (!src.hasContent()) noContent++
                    else { drawTextureLayer(layer, src, scene); drawn++ }
                }
                is LayerDefinition.Image -> {
                    val src = bitmapSources[layer.sourceId]
                    if (src == null) noSource++
                    else if (!src.hasContent()) noContent++
                    else { drawTextureLayer(layer, src, scene); drawn++ }
                }
                is LayerDefinition.Video -> {
                    val src = externalSources[layer.sourceId]
                    if (src == null) noSource++
                    else if (!src.hasContent()) noContent++
                    else { drawTextureLayer(layer, src, scene); drawn++ }
                }
                is LayerDefinition.Text -> {
                    val src = bitmapSources[layer.id]
                    if (src == null) noSource++
                    else if (!src.hasContent()) noContent++
                    else { drawTextureLayer(layer, src, scene); drawn++ }
                }
            }
        }
        r.endScene()

        presentedSceneTex = currentFbo().texture.id
        renderedFrameCount++
        lastRenderMonotonicMs = clock.nowMs()
        if (renderedFrameCount == 1L) {
            noteEvent("FIRST_SCENE_RENDER ${scene.width}x${scene.height}")
        }
        frLayerDraws = drawn
        // Round 24 mandate 2: FBO center probe at 1 Hz (30 frames @ 30fps).
        if (renderedFrameCount % 30L == 0L) {
            val px = java.nio.ByteBuffer.allocateDirect(4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFbo().handle)
            GLES30.glReadPixels(scene.width / 2, scene.height / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            probeRing.append(
                "FBO_PROBE t=${clock.nowMs()} render=$renderedFrameCount " +
                    "rgb=${px.get(0).toInt() and 0xFF},${px.get(1).toInt() and 0xFF},${px.get(2).toInt() and 0xFF}",
            )
        }
        fun hx(b: Int): String = String.format("%02X", b and 0xFF)
        // Every ~300 renders: prove whether the scene FBO actually contains
        // pixels (decides "draw path broken" vs "present path broken" from a
        // dump alone). Round 16C: sample all four corners (+2px inset) AND
        // the center — a wedge present in the FBO shows up as black corners
        // here; a clean FBO + wedged presentation isolates the present pass.
        if (renderedFrameCount % 300L == 1L) {
            val probes = listOf(
                2 to 2,                                   // TL
                scene.width - 3 to 2,                     // TR
                scene.width - 3 to scene.height - 3,      // BR
                2 to scene.height - 3,                    // BL
                scene.width / 2 to scene.height / 2,      // center
            )
            val probeVals = probes.map { (x, y) ->
                val px = java.nio.ByteBuffer.allocateDirect(4)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFbo().handle)
                GLES30.glReadPixels(x, y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                intArrayOf(px.get(0).toInt(), px.get(1).toInt(), px.get(2).toInt(), px.get(3).toInt())
            }
            val center = probeVals[4]
            val err = GLES30.glGetError()
            val first = scene.layers.firstOrNull { it.visible && it.opacity > 0.01f }
            val tInfo = first?.let {
                val t = it.transform
                " L0=${it.javaClass.simpleName}(cx=${t.centerX},cy=${t.centerY},w=${t.width},h=${t.height}," +
                    "uvRot=${t.uvRotationDeg},mx=${t.mirrorX},rot=${t.rotationDeg})"
            } ?: ""
            noteEvent(
                "DRAW_STATS drawn=$drawn noContent=$noContent noSource=$noSource$tInfo " +
                    "PRESENT_QUAD=${lastPresentQuad.toList().map { (it * 10).toInt() / 10f }} " +
                    "SCENE_PIXEL=[" + center.joinToString(",") { hx(it) } + "] " +
                    "SCENE_PIXELS=[" + probeVals.take(4).joinToString(",") { p -> "[" + p.joinToString(",") { hx(it) } + "]" } +
                    "] glErr=0x${Integer.toHexString(err)}",
            )
            if (err != 0) noteLaunch("GL_ERROR code=0x${Integer.toHexString(err)} at scene-draw frame=$renderedFrameCount")
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
        if (outputs.isEmpty() || outputs.values.all { it.gaveUp }) {
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
        if (r.bisectLevel in 1..4) {
            presentBisect(frameTimeNanos, r.bisectLevel)
            return
        }
        if (r.directSurfacePass) {
            presentDirectToSurface(frameTimeNanos, scene)
            return
        }
        if (presentedSceneTex == 0) {
            // Scene exists but nothing rendered yet — render once now so the
            // very first present already shows content.
            renderScene(scene)
        }

        val nowMs = clock.nowMs()
        val transFrac = transitionFraction(nowMs) // ONCE per frame (state-mutating)
        var anySwapOk = false

        for (out in outputs.values) {
            if (out.gaveUp) continue
            // Present-on-change: skip only when NO new render happened since
            // this output's last swap. (Regression note: gating on the scene
            // TEXTURE id was wrong — simple scenes reuse the same FBO texture
            // every frame, which froze presentation after the first swap.)
            if (transFrac == null && out.lastPresentedRender == renderedFrameCount) {
                frSwapSkipped = true // round 24: per-frame SWAP_SKIPPED field
                continue
            }
            if (!out.surface.isValid) {
                // 0x300d class: producer surface already dead — force a fresh
                // window surface instead of swapping at a corpse.
                out.needsReinit = true
                out.lastReinitReason = "surface-invalid"
                if (!reinitOutput(out)) continue
            }
            presentAttemptCount++ // reached the present path for this output
            if (out.needsReinit || out.eglSurface == null) {
                if (!reinitOutput(out)) continue
            }
            val es = out.eglSurface!!
            if (!es.makeCurrent()) {
                out.makeCurrentFailures++
                val f = out.makeCurrentFailures
                if (f == 1 || f == 30 || f % 300 == 0) {
                    noteEvent(
                        "MAKE_CURRENT_FAILED id=${out.id} n=$f err=${EGL14.eglGetError()} surfaceValid=${out.surface.isValid}",
                    )
                }
                if (f == 30) {
                    // Persistent current-bind failure: force a fresh EGL surface.
                    out.needsReinit = true
                    Log.w(TAG, "OUTPUT_RECYCLE id=${out.id} after 30 makeCurrent failures")
                }
                continue
            }
            out.makeCurrentFailures = 0

            GLES30.glViewport(0, 0, out.width, out.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val quad = r.fillQuad(scene.width, scene.height, out.width, out.height)
            lastPresentQuad = quad.cornersPx.copyOf()
            val t = transFrac
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

            // (Counting note: the attempt was already counted at the top of
            // this output's present path — a second increment here made
            // presentAttempts report ~2x presented in every dump.)
            val probeThisFrame = loopIterations % 5 == 0L && out.id == PREVIEW_OUTPUT_ID
            if (probeThisFrame) presentProbe(out, pre = true)
            es.setPresentationTime(frameTimeNanos)
            val swapStartNs = System.nanoTime()
            val swapOk = es.swap()
            lastSwapDurationMs = (System.nanoTime() - swapStartNs) / 1_000_000
            if (out.id == PREVIEW_OUTPUT_ID || frSwapMs == 0L) frSwapMs = lastSwapDurationMs
            if (lastSwapDurationMs > 100) noteEvent("SWAP_STALLED ms=$lastSwapDurationMs id=${out.id}")
            if (swapOk) {
                anySwapOk = true
                out.swapFailures = 0
                if (probeThisFrame) presentProbe(out, pre = false)
                // A GL error AFTER a successful swap must never abort the
                // frame loop or fake a zero presented-count (round-4 bug).
                runCatching { checkGlError("present(${out.id})") }
                    .onFailure {
                        Log.w(TAG, "POST_SWAP_GL_ERROR id=${out.id}: ${it.message} (swap OK, frame counted)")
                        noteLaunch("GL_ERROR code=${it.message} at present(${out.id}) post-swap")
                    }
                out.lastPresentedRender = renderedFrameCount
                if (!out.firstPresentLogged) {
                    out.firstPresentLogged = true
                    noteEvent("FIRST_PRESENT ${out.id} ${out.width}x${out.height}")
                    noteLaunch("FIRST_PRESENT dims=${out.width}x${out.height} id=${out.id}")
                }
            } else {
                out.swapFailures++
                val f = out.swapFailures
                noteEvent(
                    "SWAP_FAILED id=${out.id} n=$f err=0x${Integer.toHexString(EGL14.eglGetError())} surfaceValid=${out.surface.isValid}",
                )
                noteLaunch("SWAP_FAILED id=${out.id} n=$f")
                if (!out.surface.isValid) out.needsReinit = true
                if (f >= 30) {
                    out.needsReinit = true
                    out.lastReinitReason = "swap-failures=$f"
                    out.swapFailures = 0
                    Log.w(TAG, "OUTPUT_RECYCLE id=${out.id} after 30 swap failures")
                }
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

    /**
     * Round 19 BISECTION (owner mandate, report-only): minimal-rung renders,
     * each straight to the EGL surface; T3 renders the solid into the scene
     * FBO first, then presents it with the standard textured present quad.
     * T5 is NOT routed here (it runs the full pipeline with a plain-UV
     * override inside SceneRenderer); T6 = bisectLevel 0.
     */
    private fun presentBisect(frameTimeNanos: Long, level: Int) {
        val r = renderer ?: return
        val out = outputs.values.firstOrNull { !it.gaveUp && it.surface.isValid } ?: return
        presentAttemptCount++
        if (out.needsReinit || out.eglSurface == null) {
            if (!reinitOutput(out)) return
        }
        val es = out.eglSurface ?: return
        if (!es.makeCurrent()) return
        GLES30.glViewport(0, 0, out.width, out.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        when (level) {
            1 -> r.drawBisectSolid(fromAttrib = false, tag = "BISECT1")
            2 -> r.drawBisectSolid(fromAttrib = true, tag = "BISECT2")
            3 -> {
                val fbo = currentFbo()
                fbo.bindViewport()
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                r.drawBisectSolid(fromAttrib = true, tag = "BISECT3_FBO")
                GLES30.glDisable(GLES30.GL_BLEND)
                r.drawTextureQuad(fbo.texture.id, 1f, r.fillQuad(fbo.width, fbo.height, out.width, out.height), out.width, out.height)
            }
            4 -> {
                val src = externalSources.values.firstOrNull { it.hasContent() }
                if (src != null) {
                    r.drawBisectOes(src, out.width, out.height)
                }
            }
        }
        es.setPresentationTime(frameTimeNanos)
        if (es.swap()) {
            out.swapFailures = 0
            val nowMs = clock.nowMs()
            val dt = if (lastPresentClockMs > 0) {
                (nowMs - lastPresentClockMs).toFloat().coerceIn(1f, 500f)
            } else 16.7f
            collector.onPresented(dt, 0)
            lastPresentClockMs = nowMs
            lastPresentMonotonicMs = nowMs
            consecutiveFailedRecoveries = 0
        }
    }

    /**
     * Round 17C (owner mandate, report-only): layers render STRAIGHT to the
     * EGL window surface (framebuffer 0) — scene FBOs and the FBO->surface
     * present pass are skipped entirely. If the wedge vanishes here, the
     * FBO-to-surface path is implicated; if it persists, it is in the draw
     * itself or the window-surface state.
     */
    private fun presentDirectToSurface(frameTimeNanos: Long, scene: SceneDefinition) {
        val r = renderer ?: return
        val out = outputs.values.firstOrNull { !it.gaveUp && it.surface.isValid } ?: return
        presentAttemptCount++
        if (out.needsReinit || out.eglSurface == null) {
            if (!reinitOutput(out)) return
        }
        val es = out.eglSurface ?: return
        if (!es.makeCurrent()) return
        GLES30.glViewport(0, 0, out.width, out.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
        for (layer in scene.layers) {
            if (!layer.visible || layer.opacity <= 0.01f) continue
            when (layer) {
                is LayerDefinition.Camera -> {
                    val src = externalSources[layer.id]
                    if (src != null && src.hasContent()) {
                        r.drawLayerDirectToSurface(layer, src, out.width, out.height)
                    }
                }
                is LayerDefinition.Video -> {
                    val src = externalSources[layer.id]
                    if (src != null && src.hasContent()) {
                        r.drawLayerDirectToSurface(layer, src, out.width, out.height)
                    }
                }
                else -> Unit // color/image/text skipped in this probe
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        es.setPresentationTime(frameTimeNanos)
        if (es.swap()) {
            out.swapFailures = 0
            out.lastPresentedRender = renderedFrameCount
            val nowMs = clock.nowMs()
            val dt = if (lastPresentClockMs > 0) {
                (nowMs - lastPresentClockMs).toFloat().coerceIn(1f, 500f)
            } else 16.7f
            collector.onPresented(dt, 0)
            lastPresentClockMs = nowMs
            lastPresentMonotonicMs = nowMs
            consecutiveFailedRecoveries = 0
        }
    }

    /** Presents a cleared (background-colored) frame on outputs — never-black floor. */
    private fun presentBlank(frameTimeNanos: Long) {
        var anySwapOk = false
        for (out in outputs.values) {
            if (out.gaveUp) continue
            presentAttemptCount++
            if (out.needsReinit || out.eglSurface == null) {
                if (!reinitOutput(out)) continue
            }
            val es = out.eglSurface!!
            if (!es.makeCurrent()) {
                out.makeCurrentFailures++
                val f = out.makeCurrentFailures
                if (f == 1 || f == 30 || f % 300 == 0) {
                    noteEvent(
                        "MAKE_CURRENT_FAILED id=${out.id} n=$f err=${EGL14.eglGetError()} surfaceValid=${out.surface.isValid} (blank)",
                    )
                }
                if (f == 30) out.needsReinit = true
                continue
            }
            out.makeCurrentFailures = 0
            GLES30.glViewport(0, 0, out.width, out.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            es.setPresentationTime(frameTimeNanos)
            val blankStartNs = System.nanoTime()
            val blankOk = es.swap()
            lastSwapDurationMs = (System.nanoTime() - blankStartNs) / 1_000_000
            if (blankOk) {
                anySwapOk = true
                if (!out.firstPresentLogged) {
                    out.firstPresentLogged = true
                    Log.i(TAG, "FIRST_PRESENT ${out.id} ${out.width}x${out.height} (blank)")
                    noteLaunch("FIRST_PRESENT dims=${out.width}x${out.height} id=${out.id} blank")
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

    fun watchdogStatus(): Watchdog.WatchdogStatus = Watchdog.WatchdogStatus(
        expectsFrames, lastPresentMonotonicMs, lastRenderMonotonicMs,
    )

    companion object {
        private const val TAG = "vcam-render"
        const val PREVIEW_OUTPUT_ID = "preview"
        const val RECORDING_OUTPUT_ID = "recording"
        const val DEFAULT_SOURCE_W = 1280
        const val DEFAULT_SOURCE_H = 720
    }
}


/**
 * Round 24 mandate 1: fixed-capacity ring for per-frame diagnostic lines.
 * Single writer (render thread) — lock-free, write index only. The dump
 * snapshots slots in emission order; a slot the writer is mid-write is
 * skipped (tears are acceptable: the next frame carries the signal).
 */
private class FrameRing(private val capacity: Int) {
    private val slots = arrayOfNulls<String>(capacity)
    private val writeIndex = java.util.concurrent.atomic.AtomicLong(0)

    fun append(line: String) {
        val idx = writeIndex.getAndIncrement()
        slots[(idx % capacity).toInt()] = line
    }

    /** Last <=capacity entries oldest -> newest. */
    fun snapshot(): List<String> {
        val total = writeIndex.get()
        val start = (total - capacity).coerceAtLeast(0)
        val out = ArrayList<String>(capacity.toInt())
        for (i in start until total) {
            slots[(i % capacity).toInt()]?.let { out.add(it) }
        }
        return out
    }
}
