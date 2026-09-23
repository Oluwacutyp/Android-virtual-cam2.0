package com.vcamstudio.engine.render.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.vcamstudio.engine.render.geometry.LayerGeometry
import com.vcamstudio.engine.render.geometry.SourceUvMath
import com.vcamstudio.engine.render.gl.Framebuffer
import com.vcamstudio.engine.render.gl.GlProgram
import com.vcamstudio.engine.render.gl.LutCache
import com.vcamstudio.engine.render.gl.checkGlError
import com.vcamstudio.engine.render.model.BlendMode
import com.vcamstudio.engine.render.model.FitMode
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.LayerTransform
import com.vcamstudio.engine.render.shaders.Shaders
import com.vcamstudio.engine.render.source.BitmapTextureSource
import com.vcamstudio.engine.render.source.ExternalTextureSource
import com.vcamstudio.engine.render.source.TextureSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Executes all GL drawing for scene frames. Holds no lifecycle state — FBOs
 * and scheduling belong to [RenderThread]; this class is the "how to draw".
 *
 * Alpha convention: everything premultiplied. Content layers write
 * premultiplied color; hardware blending is (ONE, ONE_MINUS_SRC_ALPHA).
 * FBO content lives in GL-native orientation; every pass that samples an FBO
 * applies the Y flip exactly once via [uploadFullScreenQuad] (flipY = true).
 * Bitmap textures from GLUtils and OES producer matrices are handled in the
 * layer draw path, so they composite consistently.
 */
internal class SceneRenderer(
    private val programs: Shaders.Programs,
    val lutCache: LutCache = LutCache(),
) {

    // Separate buffers per attribute — no offset arithmetic anywhere.
    private val staging = FloatArray(STRIDE_FLOATS * 4)
    private val posBuf = directFloatBuffer(8)
    private val uvBuf = directFloatBuffer(8)
    private val localBuf = directFloatBuffer(8)

    fun enableVertexArrays() {
        for (i in 0..2) GLES30.glEnableVertexAttribArray(i)
    }

    // ---------------------------------------------------------------- scene

    fun beginScene(target: Framebuffer, backgroundArgb: Long) {
        target.bindViewport()
        GLES30.glClearColor(red(backgroundArgb), green(backgroundArgb), blue(backgroundArgb), alphaF(backgroundArgb))
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
    }

    fun endScene() {
        GLES30.glDisable(GLES30.GL_BLEND)
        checkGlError("endScene")
    }

    // --------------------------------------------------------------- layers

    /** Fast path: texture layer straight into the scene FBO with hardware NORMAL blend. */
    fun drawLayerDirect(
        layer: LayerDefinition,
        source: TextureSource,
        target: Framebuffer,
        sceneW: Int,
        sceneH: Int,
    ) {
        // DEV DIAGNOSTIC (round 16A): UV-gradient pass replaces sampling for
        // external sources when enabled from the dev diagnostics toggle.
        val program =
            if (uvDebugPass && source is ExternalTextureSource) programs.uvDebug
            else programFor(source, layer.effects.lutId)
        val transform = layer.transform
        val quad = LayerGeometry.compute(
            transform, source.width.toFloat(), source.height.toFloat(), sceneW.toFloat(), sceneH.toFloat(),
        )
        val drawW = edgeLengthPx(quad, topEdge = true)
        val drawH = edgeLengthPx(quad, topEdge = false)

        target.bindViewport()
        program.use()
        uploadQuad(quad, sceneW.toFloat(), sceneH.toFloat())
        bindSource(program, source, layer.transform.uvRotationDeg, layer.transform.mirrorX)
        bindLut(program, layer.effects.lutId)
        setFxUniforms(program, layer, source.width, source.height, drawW, drawH)
        if (source is ExternalTextureSource) {
            val kind = if (program === programs.uvDebug) "UVDBG" else "OES"
            captureDrawState("$kind:${layer.id}", program)
        }
        if (vboDrawPass && source is ExternalTextureSource) drawQuadVbo() else {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        checkGlError("drawLayerDirect(${layer.id})")
    }

    /** Effects path: layer rendered isolated into [scratch] (blur applied). */
    fun drawLayerToScratch(
        layer: LayerDefinition,
        source: TextureSource,
        scratch: Framebuffer,
        blurScratch: Framebuffer,
        sceneW: Int,
        sceneH: Int,
    ) {
        // DEV DIAGNOSTIC (round 16A): same UV-gradient substitution as the
        // direct path (see drawLayerDirect).
        val program =
            if (uvDebugPass && source is ExternalTextureSource) programs.uvDebug
            else programFor(source, layer.effects.lutId)
        val transform = layer.transform
        val quad = LayerGeometry.compute(
            transform, source.width.toFloat(), source.height.toFloat(), sceneW.toFloat(), sceneH.toFloat(),
        )
        val drawW = edgeLengthPx(quad, topEdge = true)
        val drawH = edgeLengthPx(quad, topEdge = false)

        scratch.bindViewport()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)

        program.use()
        uploadQuad(quad, sceneW.toFloat(), sceneH.toFloat())
        bindSource(program, source, layer.transform.uvRotationDeg, layer.transform.mirrorX)
        bindLut(program, layer.effects.lutId)
        setFxUniforms(program, layer, source.width, source.height, drawW, drawH, opacity = 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (layer.effects.blur.isEnabled) {
            gaussianBlur(scratch, blurScratch, layer.effects.blur.radius, sceneW, sceneH)
        }
    }

    /** Composites [scratch] (layer) over [accumulated] (scene) with a blend mode into [dest]. */
    fun blendScratchOnto(
        accumulated: Framebuffer,
        dest: Framebuffer,
        scratch: Framebuffer,
        opacity: Float,
        blendMode: BlendMode,
    ) {
        dest.bindViewport()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)

        programs.blend.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scratch.texture.id)
        programs.blend.setInt("uSrc", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, accumulated.texture.id)
        programs.blend.setInt("uDst", 1)
        programs.blend.setInt("uMode", blendMode.glslId)
        programs.blend.setFloat("uOpacity", opacity)
        uploadFullScreenQuad(flipY = true)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGlError("blendScratchOnto")
    }

    fun drawColorLayer(layer: LayerDefinition.Color, target: Framebuffer, sceneW: Int, sceneH: Int) {
        val transform = layer.transform
        val quad = LayerGeometry.compute(
            transform, 1f, 1f, sceneW.toFloat(), sceneH.toFloat(),
        )
        val drawW = edgeLengthPx(quad, topEdge = true)
        val drawH = edgeLengthPx(quad, topEdge = false)

        target.bindViewport()
        programs.fill.use()
        val argb = layer.color.toLong() and 0xFFFFFFFFL
        programs.fill.setVec4("uColor", red(argb), green(argb), blue(argb), alphaF(argb))
        programs.fill.setFloat("uOpacity", layer.opacity)
        programs.fill.setVec2("uQuadSizePx", drawW, drawH)
        programs.fill.setFloat(
            "uCornerPx",
            LayerGeometry.cornerRadiusPx(transform.cornerRadius, drawW, drawH),
        )
        uploadQuad(quad, sceneW.toFloat(), sceneH.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("drawColorLayer")
    }

    /** FBO -> FBO copy (transition capture). */
    fun copyFbo(src: Framebuffer, dst: Framebuffer) {
        dst.bindViewport()
        GLES30.glDisable(GLES30.GL_BLEND)
        programs.copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.texture.id)
        programs.copy.setInt("uTex", 0)
        programs.copy.setFloat("uAlpha", 1f)
        uploadFullScreenQuad(flipY = true)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Samples a 2D FBO texture onto an arbitrary quad of the current target (present path). */
    fun drawTextureQuad(
        textureId: Int,
        alpha: Float,
        quad: LayerGeometry.Quad,
        surfaceW: Int,
        surfaceH: Int,
    ) {
        programs.copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        programs.copy.setInt("uTex", 0)
        programs.copy.setFloat("uAlpha", alpha)
        // Source is an FBO (GL orientation) -> flip UVs so the image reads upright.
        uploadQuad(quad, surfaceW.toFloat(), surfaceH.toFloat(), uvFlipY = true)
        capturePresentDebug()
        captureDrawState("PRESENT", programs.copy)
        if (vboDrawPass) drawQuadVbo() else GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Present quad that COVERS the output (FILL): the scene fully covers the
     * preview with no letterbox bars (edges crop when aspects differ). For
     * same-aspect outputs (the recorder) FILL is identical to FIT.
     */
    fun fillQuad(sceneW: Int, sceneH: Int, outW: Int, outH: Int): LayerGeometry.Quad =
        LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FILL),
            sceneW.toFloat(), sceneH.toFloat(), outW.toFloat(), outH.toFloat(),
        )

    // -------------------------------------------------------------- helpers

    private fun programFor(source: TextureSource, lutId: String?): GlProgram {
        val oes = source.target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        return when {
            oes && lutId != null -> programs.texOesLut
            oes -> programs.texOes
            lutId != null -> programs.tex2dLut
            else -> programs.tex2d
        }
    }

    /** Binds the layer's LUT (unit 1) or zeroes the mix for plain passes. */
    private fun bindLut(program: GlProgram, lutId: String?) {
        val tex = lutId?.let { lutCache.get(it) }
        if (tex != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
            program.setInt("uLut3d", 1)
            program.setFloat("uLutMix", 1f)
        } else {
            program.setFloat("uLutMix", 0f)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun bindSource(
        program: GlProgram,
        source: TextureSource,
        uvRotationDeg: Float = 0f,
        mirrorX: Boolean = false,
    ) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(source.target, source.glTextureId)
        program.setInt("uTex", 0)
        // Canonical source-orientation chain — SourceUvMath (golden-tested, the
        // ONLY place source rotation/mirror happen):
        //   ST matrix (buffer flip/crop) -> mirror -> rotation about the
        //   window center -> clamp. Both transforms AFTER the ST matrix (the
        //   buffer flip conjugates them otherwise) and the mirror BEFORE the
        //   rotation (the post-ST frame is display-aligned; mirroring in the
        //   rotated frame conjugates into a vertical flip).
        //   Sampling angle == the source's metadata rotation (device-
        //   calibrated round 14: 0 -> 90 off, 90 -> upside-down, sensor -> up).
        //   The final clamp makes OOB OES sampling (diagonal black wedge
        //   class) impossible even if a future matrix misbehaves.
        val st: FloatArray?
        if (source is ExternalTextureSource) {
            System.arraycopy(source.transformMatrix, 0, stScratch, 0, 16)
            st = stScratch
        } else {
            st = null
        }
        for (i in 0 until 4) {
            uvBase[i * 2] = uvBuf.get(i * 2)
            uvBase[i * 2 + 1] = uvBuf.get(i * 2 + 1)
        }
        SourceUvMath.transformInto(uvBase, st, uvRotationDeg, mirrorX, uvWork)
        for (i in 0 until 4) {
            uvBuf.put(i * 2, uvWork[i * 2])
            uvBuf.put(i * 2 + 1, uvWork[i * 2 + 1])
        }
        uvBuf.position(0)
        if (source is ExternalTextureSource) captureOesDebug(program, source, uvRotationDeg, mirrorX)
    }

    /**
     * DEV DIAGNOSTIC (round 16A): when true, external-source layers render
     * with the UV-gradient program (no OES sample). Dev builds only — the
     * toggle lives in the diagnostics sheet behind a debuggable check.
     */
    @Volatile
    var uvDebugPass: Boolean = false

    // ---- orientation diagnostics: 1 Hz snapshot surfaced in the engine dump ----

    private val stScratch = FloatArray(16)
    private val uvBase = FloatArray(8)
    private val uvWork = FloatArray(8)
    private var lastOesDebugMs = 0L

    @Volatile
    var oesDebug: String? = null
        private set

    /**
     * DIAGNOSTIC (round 16B-ad): 1 Hz capture of the PRESENT pass's four clip
     * positions (the camera-draw CLIP line cannot see this pass). The
     * screenshot-measured wedge signature — apex exactly at the frame center,
     * converging edges — is what a TRIANGLE_STRIP produces when its two
     * left-column vertices render at clip (0,0): this line proves or refutes
     * that live for the present path.
     */
    @Volatile
    var presentDebug: String? = null
        private set

    private var lastPresentDebugMs = 0L

    private fun capturePresentDebug() {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastPresentDebugMs < 1000) return
        lastPresentDebugMs = now
        val finite = (0 until 8).all {
            val v = posBuf.get(it)
            !v.isNaN() && !v.isInfinite()
        }
        presentDebug = "PRESENT_CLIP=" + (0 until 4).joinToString(";", "[", "]") { i ->
            String.format(Locale.US, "%.3f,%.3f", posBuf.get(i * 2), posBuf.get(i * 2 + 1))
        } + " finite=$finite"
        Log.i("vcam-render", presentDebug!!)
    }

    /**
     * DEV TEST (round 16D-3, owner-mandated one-shot): when enabled, quad
     * draws run through a FRESHLY created VBO (+ VAO bound only around the
     * test draw) with a per-draw glBufferData upload of the exact staged
     * values — no client-side arrays, no buffer reuse across draws. If the
     * wedge disappears with the wedge visible before/after toggling, the
     * client-array consumption path is the culprit; if it persists with a
     * completely isolated buffer, the bug is elsewhere. Default OFF.
     */
    @Volatile
    var vboDrawPass: Boolean = false

    private var testVbo = 0
    private var testVao = 0
    private val testInterleaved = directFloatBuffer(STRIDE_FLOATS * 4)

    private fun drawQuadVbo() {
        if (testVbo == 0) {
            val vbo = IntArray(1)
            val vao = IntArray(1)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glGenVertexArrays(1, vao, 0)
            testVbo = vbo[0]
            testVao = vao[0]
            GLES30.glBindVertexArray(testVao)
            GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, testVbo)
            for (i in 0..2) GLES30.glEnableVertexAttribArray(i)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, STRIDE_FLOATS * 4, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE_FLOATS * 4, 8)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE_FLOATS * 4, 16)
            GLES30.glBindVertexArray(0)
            noteVboCreated(testVbo, testVao)
        }
        testInterleaved.clear()
        for (f in staging) testInterleaved.put(f)
        testInterleaved.position(0)
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, testVbo)
        GLES30.glBufferData(GLES20.GL_ARRAY_BUFFER, STRIDE_FLOATS * 4 * 4, testInterleaved, GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(testVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    @Volatile
    var vboCreatedNote: String? = null
        private set

    private fun noteVboCreated(vbo: Int, vao: Int) {
        vboCreatedNote = "VBO_TEST_CREATED vbo=$vbo vao=$vao"
        Log.i("vcam-render", vboCreatedNote!!)
    }

    // ---- (round 16C) DRAW-STATE AUDIT: the wedge survives a no-sample shader,
    // so the vertex pipeline itself is under investigation. Captured AT DRAW
    // TIME, 1 Hz per tag: current program vs expected, per-attribute vertex
    // array state (enabled/size/type/stride/normalized/BUFFER), global array
    // + element bindings, the draw call form, cull/winding, and the exact
    // CPU-side position/UV mirrors the draw consumes (client arrays: buf=0
    // proves the dump reads the SAME data the GPU reads).

    @Volatile
    var drawStateDebug: String? = null
        private set

    @Volatile
    var presentDrawStateDebug: String? = null
        private set

    private val drawStateLastMs = HashMap<String, Long>()

    private fun captureDrawState(tag: String, program: GlProgram) {
        val now = System.nanoTime() / 1_000_000L
        synchronized(drawStateLastMs) {
            val last = drawStateLastMs[tag]
            if (last != null && now - last < 1000) return
            drawStateLastMs[tag] = now
        }
        val cur = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, cur, 0)
        fun attrib(i: Int): String {
            val en = IntArray(1); val size = IntArray(1); val type = IntArray(1)
            val stride = IntArray(1); val norm = IntArray(1); val buf = IntArray(1)
            GLES30.glGetVertexAttribiv(i, GLES30.GL_VERTEX_ATTRIB_ARRAY_ENABLED, en, 0)
            GLES30.glGetVertexAttribiv(i, GLES30.GL_VERTEX_ATTRIB_ARRAY_SIZE, size, 0)
            GLES30.glGetVertexAttribiv(i, GLES30.GL_VERTEX_ATTRIB_ARRAY_TYPE, type, 0)
            GLES30.glGetVertexAttribiv(i, GLES30.GL_VERTEX_ATTRIB_ARRAY_STRIDE, stride, 0)
            GLES30.glGetVertexAttribiv(i, GLES30.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, norm, 0)
            GLES30.glGetVertexAttribiv(i, GLES20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, buf, 0)
            return "en=${en[0]},size=${size[0]},type=0x${Integer.toHexString(type[0])}," +
                "stride=${stride[0]},norm=${norm[0]},buf=${buf[0]}"
        }
        val arrayBuf = IntArray(1)
        val elemBuf = IntArray(1)
        val vaoBinding = IntArray(1)
        GLES30.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, arrayBuf, 0)
        GLES30.glGetIntegerv(GLES20.GL_ELEMENT_ARRAY_BUFFER_BINDING, elemBuf, 0)
        // 0x80B5 = GL_VERTEX_ARRAY_BINDING (literal: constant lives in ES3 headers
        // but the engine uses NO VAOs — expect 0; reported so it is PROVEN per dump)
        GLES30.glGetIntegerv(0x80B5, vaoBinding, 0)
        val cullOn = GLES30.glIsEnabled(GLES30.GL_CULL_FACE)
        val cullMode = IntArray(1)
        val frontFace = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_CULL_FACE_MODE, cullMode, 0)
        GLES30.glGetIntegerv(GLES30.GL_FRONT_FACE, frontFace, 0)
        val uv = (0 until 4).joinToString(";", "[", "]") { i ->
            String.format(Locale.US, "%.3f,%.3f", uvBuf.get(i * 2), uvBuf.get(i * 2 + 1))
        }
        val clip = (0 until 4).joinToString(";", "[", "]") { i ->
            String.format(Locale.US, "%.3f,%.3f", posBuf.get(i * 2), posBuf.get(i * 2 + 1))
        }
        // Raw bytes the draw consumes (mandate 16D-2). Client arrays: the CPU
        // staging IS the source (nothing to map) — log its 96 bytes as
        // float-bit hex. VBO test path: map the bound buffer and log ITS bytes.
        fun floatWords(buf: java.nio.FloatBuffer, n: Int): String =
            (0 until n).joinToString(",") {
                String.format(Locale.US, "%08X", java.lang.Float.floatToIntBits(buf.get(it)))
            }
        val clientBytes = "pos:[" + floatWords(posBuf, 8) + "] uv:[" + floatWords(uvBuf, 8) + "]" +
            " loc:[" + floatWords(localBuf, 8) + "]"
        var gpuBytes = "n/a(client-array)"
        if (arrayBuf[0] != 0) {
            val mapped = GLES30.glMapBufferRange(
                GLES20.GL_ARRAY_BUFFER, 0, 96, GLES30.GL_MAP_READ_BIT,
            ) as java.nio.ByteBuffer?
            if (mapped != null) {
                mapped.order(java.nio.ByteOrder.nativeOrder())
                val words = mapped.asIntBuffer()
                gpuBytes = (0 until 24).joinToString(",") {
                    String.format(Locale.US, "%08X", words.get(it))
                }
                GLES30.glUnmapBuffer(GLES20.GL_ARRAY_BUFFER)
            } else {
                gpuBytes = "mapFailed=0x" + Integer.toHexString(GLES30.glGetError())
            }
        }
        val line = buildString {
            append("DRAW_STATE[").append(tag).append("]")
            append(" prog=").append(program.handle)
            append(" curProg=").append(cur[0])
            append(" attr0=[").append(attrib(0)).append("]")
            append(" attr1=[").append(attrib(1)).append("]")
            append(" attr2=[").append(attrib(2)).append("]")
            append(" vao=").append(vaoBinding[0])
            append(" arrayBuf=").append(arrayBuf[0])
            append(" elemBuf=").append(elemBuf[0])
            append(" draw=glDrawArrays(TRIANGLE_STRIP,0,4)")
            append(" cull=").append(if (cullOn) "on,mode=0x${Integer.toHexString(cullMode[0])}" else "off")
            append(" frontFace=0x").append(Integer.toHexString(frontFace[0]))
            append(" CLIP=").append(clip)
            append(" UV=").append(uv)
            append(" CLIENT_BYTES=").append(clientBytes)
            append(" GPU_BYTES=[").append(gpuBytes).append("]")
        }
        if (tag == "PRESENT") presentDrawStateDebug = line else drawStateDebug = line
        Log.i("vcam-render", line)
    }

    /** The exact shader sources of the program last used for the camera draw. */
    @Volatile
    var lastOesProgramSources: Pair<String, String>? = null
        private set

    private fun captureOesDebug(program: GlProgram, src: ExternalTextureSource, rot: Float, mirror: Boolean) {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastOesDebugMs < 1000) return
        lastOesDebugMs = now
        lastOesProgramSources = program.vertexSource to program.fragmentSource
        val st = src.transformMatrix
        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        // (B) vertex dump: gl_Position = vec4(aPos, 0, 1) with aPos exactly as
        // uploaded by uploadQuad — read the live position buffer (pass-through
        // vertex shader, so these ARE the post-MVP clip xy values).
        val clip = (0 until 4).joinToString(";", "[", "]") { i ->
            String.format(Locale.US, "%.3f,%.3f", posBuf.get(i * 2), posBuf.get(i * 2 + 1))
        }
        val finite = (0 until 8).all {
            val p = posBuf.get(it); val w = uvWork.get(it)
            !p.isNaN() && !p.isInfinite() && !w.isNaN() && !w.isInfinite()
        }
        // (D) bind audit: runtime target binding + the declared sampler TYPE
        // (read from the program's RETAINED compile source — verbatim, see E)
        // + the sampler unit value.
        val bound = IntArray(1)
        GLES30.glGetIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, bound, 0)
        val samplerType = when {
            program.fragmentSource.contains("samplerExternalOES") -> "OES"
            program.fragmentSource.contains("sampler2D") -> "2D"
            else -> "unknown"
        }
        val unit = IntArray(1)
        val uTexLoc = GLES30.glGetUniformLocation(program.handle, "uTex")
        if (uTexLoc >= 0) GLES30.glGetUniformiv(program.handle, uTexLoc, unit, 0)
        val samplerUnit = if (uTexLoc >= 0) unit[0] else -1
        val bindOk = bound[0] == src.glTextureId
        // (F) ST interpretation check data: transposed matrix + NET class.
        val stT = FloatArray(16) { c -> st[(c % 4) * 4 + c / 4] }
        val net = SourceUvMath.classifyNet(st, rot, mirror, uvBase)
        val line = buildString {
            append("OES_ORIENT id=").append(src.sourceId)
            append(" src=").append(src.width).append('x').append(src.height)
            append(" uvRot=").append(rot).append(" mirrorX=").append(mirror)
            append(" ST=[").append((0 until 16).joinToString(",") { f(st[it]) }).append("]")
            append(" ST_T=[").append((0 until 16).joinToString(",") { f(stT[it]) }).append("]")
            append(" baseUV=").append((0 until 4).joinToString(";", "[", "]") { i ->
                String.format(Locale.US, "%.3f,%.3f", uvBase[i * 2], uvBase[i * 2 + 1])
            })
            append(" finalUV=").append((0 until 4).joinToString(";", "[", "]") { i ->
                String.format(Locale.US, "%.3f,%.3f", uvWork[i * 2], uvWork[i * 2 + 1])
            })
            append(" CLIP=").append(clip)
            append(" finite=").append(finite)
            append(" bind=[tgtOk=").append(bindOk)
            append(",boundId=").append(bound[0]).append(",srcId=").append(src.glTextureId)
            append(",sampler=").append(samplerType).append(",unit=").append(samplerUnit).append("]")
            append(" NET=").append(net)
        }
        oesDebug = line
        Log.i("vcam-render", line)
    }

    private fun setFxUniforms(
        program: GlProgram,
        layer: LayerDefinition,
        srcW: Int,
        srcH: Int,
        drawW: Float,
        drawH: Float,
        opacity: Float = layer.opacity,
    ) {
        val fx = layer.effects
        val grade = fx.colorGrade
        program.setFloat("uOpacity", opacity)
        program.setVec2("uTexelSize", 1f / srcW.coerceAtLeast(1), 1f / srcH.coerceAtLeast(1))
        program.setFloat("uBrightness", grade.brightness)
        program.setFloat("uContrast", grade.contrast)
        program.setFloat("uSaturation", grade.saturation)
        program.setFloat("uGamma", grade.gamma)
        program.setFloat("uTemperature", grade.temperature)
        program.setFloat("uTint", grade.tint)
        // Sharpen needs random-access sampling: 2D textures only.
        val sharpen = if (program === programs.tex2d || program === programs.tex2dLut) fx.sharpen.amount else 0f
        program.setFloat("uSharpen", sharpen)
        program.setVec3("uVignette", fx.vignette.start, fx.vignette.end, fx.vignette.strength)
        program.setVec2("uQuadSizePx", drawW, drawH)
        program.setFloat(
            "uCornerPx",
            LayerGeometry.cornerRadiusPx(layer.transform.cornerRadius, drawW, drawH),
        )
    }

    private fun gaussianBlur(src: Framebuffer, temp: Framebuffer, radius: Float, sceneW: Int, sceneH: Int) {
        val clamped = radius.coerceIn(1f, 64f)
        val iterations = if (clamped > 16f) 2 else 1
        val perIteration = clamped / (iterations * 2f)
        var read = src
        var write = temp
        repeat(iterations * 2) { pass ->
            val horizontal = pass % 2 == 0
            write.bindViewport()
            GLES30.glDisable(GLES30.GL_BLEND)
            programs.blur.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, read.texture.id)
            programs.blur.setInt("uTex", 0)
            if (horizontal) {
                programs.blur.setVec2("uDir", perIteration / sceneW, 0f)
            } else {
                programs.blur.setVec2("uDir", 0f, perIteration / sceneH)
            }
            uploadFullScreenQuad(flipY = true)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            val t = read; read = write; write = t
        }
        // Pass count is even, so the result always ends back in src.
        checkGlError("gaussianBlur")
    }

    // ---------------------------------------------------------- quad upload

    private fun uploadQuad(quad: LayerGeometry.Quad, sceneW: Float, sceneH: Float, uvFlipY: Boolean = false) {
        // cornersPx/uvs are TIGHTLY-PACKED 8-float arrays (4 verts x 2): index
        // them by VERTEX (i*2), never by the interleaved staging stride. The
        // previous code advanced the source index by STRIDE_FLOATS(6) and hit
        // index 12 of an 8-length array on EVERY device — the GL path could
        // never have worked (rounds 1-7 root cause, surfaced only once the
        // present path finally executed).
        require(quad.cornersPx.size >= 8 && quad.uvs.size >= 8) { "quad arrays too small" }
        for (i in 0 until 4) {
            val src = i * 2
            val dst = i * STRIDE_FLOATS
            val cx = quad.cornersPx[src]
            val cy = quad.cornersPx[src + 1]
            val u = quad.uvs[src]
            val v = if (uvFlipY) 1f - quad.uvs[src + 1] else quad.uvs[src + 1]
            staging[dst] = cx / sceneW * 2f - 1f
            staging[dst + 1] = 1f - cy / sceneH * 2f
            staging[dst + 2] = u
            staging[dst + 3] = v
            staging[dst + 4] = LOCAL_CORNERS[i][0]
            staging[dst + 5] = LOCAL_CORNERS[i][1]
        }
        uploadVertexData()
    }

    private fun uploadFullScreenQuad(flipY: Boolean) {
        // Same vertex-vs-stride indexing rule as uploadQuad (see its comment):
        // corners/uvs are 8-float packed arrays indexed by i*2.
        val corners = floatArrayOf(-1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f)
        val vTop = if (flipY) 1f else 0f
        val vBottom = if (flipY) 0f else 1f
        val uvs = floatArrayOf(0f, vTop, 1f, vTop, 1f, vBottom, 0f, vBottom)
        for (i in 0 until 4) {
            val src = i * 2
            val dst = i * STRIDE_FLOATS
            staging[dst] = corners[src]; staging[dst + 1] = corners[src + 1]
            staging[dst + 2] = uvs[src]; staging[dst + 3] = uvs[src + 1]
            staging[dst + 4] = LOCAL_CORNERS[i][0]; staging[dst + 5] = LOCAL_CORNERS[i][1]
        }
        uploadVertexData()
    }

    private fun uploadVertexData() {
        posBuf.clear(); uvBuf.clear(); localBuf.clear()
        var s = 0
        for (i in 0 until 4) {
            posBuf.put(staging[s]); posBuf.put(staging[s + 1])
            uvBuf.put(staging[s + 2]); uvBuf.put(staging[s + 3])
            localBuf.put(staging[s + 4]); localBuf.put(staging[s + 5])
            s += STRIDE_FLOATS
        }
        posBuf.position(0); uvBuf.position(0); localBuf.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuf)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uvBuf)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, localBuf)
    }

    private fun edgeLengthPx(quad: LayerGeometry.Quad, topEdge: Boolean): Float {
        // Top edge: TL->TR (corner 0 -> 1). Left edge: TL->BL (corner 0 -> 3).
        val i1 = if (topEdge) 1 else 3
        val dx = quad.cornersPx[i1 * 2] - quad.cornersPx[0]
        val dy = quad.cornersPx[i1 * 2 + 1] - quad.cornersPx[1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun red(argb: Long): Float = ((argb ushr 16) and 0xFFL).toFloat() / 255f
    private fun green(argb: Long): Float = ((argb ushr 8) and 0xFFL).toFloat() / 255f
    private fun blue(argb: Long): Float = (argb and 0xFFL).toFloat() / 255f
    private fun alphaF(argb: Long): Float = ((argb ushr 24) and 0xFFL).toFloat() / 255f

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        private const val STRIDE_FLOATS = 6
        private val LOCAL_CORNERS = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f),
        )
    }
}
