package com.vcamstudio.engine.render.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.vcamstudio.engine.render.geometry.LayerGeometry
import com.vcamstudio.engine.render.gl.Framebuffer
import com.vcamstudio.engine.render.gl.GlProgram
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
internal class SceneRenderer(private val programs: Shaders.Programs) {

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
        val program = programFor(source)
        val transform = layer.transform
        val quad = LayerGeometry.compute(
            transform, source.width.toFloat(), source.height.toFloat(), sceneW.toFloat(), sceneH.toFloat(),
        )
        val drawW = edgeLengthPx(quad, topEdge = true)
        val drawH = edgeLengthPx(quad, topEdge = false)

        target.bindViewport()
        program.use()
        uploadQuad(quad, sceneW.toFloat(), sceneH.toFloat())
        bindSource(program, source)
        setFxUniforms(program, layer, source.width, source.height, drawW, drawH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
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
        val program = programFor(source)
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
        bindSource(program, source)
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
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Letterbox quad that fits scene content into a differently-shaped output. */
    fun letterboxQuad(sceneW: Int, sceneH: Int, outW: Int, outH: Int): LayerGeometry.Quad =
        LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FIT),
            sceneW.toFloat(), sceneH.toFloat(), outW.toFloat(), outH.toFloat(),
        )

    // -------------------------------------------------------------- helpers

    private fun programFor(source: TextureSource): GlProgram =
        if (source.target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) programs.texOes else programs.tex2d

    private fun bindSource(program: GlProgram, source: TextureSource) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(source.target, source.glTextureId)
        program.setInt("uTex", 0)
        if (source is ExternalTextureSource) {
            applyStMatrixToUv(source.transformMatrix)
        }
    }

    /** Transforms the uploaded UVs through a SurfaceTexture producer matrix. */
    private fun applyStMatrixToUv(st: FloatArray) {
        val vec = FloatArray(4)
        val out = FloatArray(4)
        for (i in 0 until 4) {
            vec[0] = uvBuf.get(i * 2)
            vec[1] = uvBuf.get(i * 2 + 1)
            vec[2] = 0f
            vec[3] = 1f
            Matrix.multiplyMV(out, 0, st, 0, vec, 0)
            uvBuf.put(i * 2, out[0])
            uvBuf.put(i * 2 + 1, out[1])
        }
        uvBuf.position(0)
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
        val sharpen = if (program === programs.tex2d) fx.sharpen.amount else 0f
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
        val local = LOCAL_CORNERS
        var s = 0
        for (i in 0 until 4) {
            val cx = quad.cornersPx[s]
            val cy = quad.cornersPx[s + 1]
            val v = if (uvFlipY) 1f - quad.uvs[s + 1] else quad.uvs[s + 1]
            staging[s] = cx / sceneW * 2f - 1f
            staging[s + 1] = 1f - cy / sceneH * 2f
            staging[s + 2] = quad.uvs[s]
            staging[s + 3] = v
            staging[s + 4] = local[i][0]
            staging[s + 5] = local[i][1]
            s += STRIDE_FLOATS
        }
        uploadVertexData()
    }

    private fun uploadFullScreenQuad(flipY: Boolean) {
        val corners = floatArrayOf(-1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f)
        val vTop = if (flipY) 1f else 0f
        val vBottom = if (flipY) 0f else 1f
        val uvs = floatArrayOf(0f, vTop, 1f, vTop, 1f, vBottom, 0f, vBottom)
        var s = 0
        for (i in 0 until 4) {
            staging[s] = corners[s]; staging[s + 1] = corners[s + 1]
            staging[s + 2] = uvs[s]; staging[s + 3] = uvs[s + 1]
            staging[s + 4] = LOCAL_CORNERS[i][0]; staging[s + 5] = LOCAL_CORNERS[i][1]
            s += STRIDE_FLOATS
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
