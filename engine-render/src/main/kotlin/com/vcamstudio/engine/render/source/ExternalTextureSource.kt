package com.vcamstudio.engine.render.source

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine-owned external texture fed by an outside producer (CameraX or a video
 * player) through a [SurfaceTexture]. The engine creates it on the render
 * thread and hands the [surface] to the app; the producer writes frames, the
 * render thread consumes them via updateTexImage.
 *
 * Frame-available callbacks arrive on the render thread's looper (the source
 * is constructed there), so we only flip an atomic flag here — never GL work.
 */
class ExternalTextureSource(
    override val sourceId: String,
    bufferWidth: Int,
    bufferHeight: Int,
) : TextureSource {

    override var width: Int = bufferWidth
        private set

    override var height: Int = bufferHeight
        private set

    override val target: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

    override val glTextureId: Int = glGenOesTexture()

    private val framePending = AtomicBoolean(false)
    private var hasFrame = false
    private var frameCount = 0L

    @Suppress("DEPRECATION") // single-arg ctor is the supported pattern for GL-owned textures
    val surfaceTexture: SurfaceTexture = SurfaceTexture(glTextureId).also {
        it.setDefaultBufferSize(bufferWidth, bufferHeight)
        it.setOnFrameAvailableListener { framePending.set(true) }
    }

    val surface: Surface = Surface(surfaceTexture)

    /** Transform matrix the producer wants applied to UVs (read on render thread). */
    val transformMatrix = FloatArray(16)

    override fun update(): Boolean {
        if (!framePending.compareAndSet(true, false)) return false
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
        } catch (t: Throwable) {
            // Abandoned/tearing-down producer: skip this frame, stay alive.
            android.util.Log.w("vcam-render", "UPDATE_TEX_IMAGE_FAILED $sourceId: ${t.message}")
            return false
        }
        // SurfaceTexture has no public size getters; dimensions stay at the
        // requested buffer size (the producer honors setDefaultBufferSize).
        if (!hasFrame) {
            android.util.Log.i("vcam-render", "FIRST_CAMERA_FRAME $sourceId")
        }
        hasFrame = true
        frameCount++
        if (frameCount == 1L || frameCount % 60L == 0L) {
            android.util.Log.i("vcam-render", "FRAME_FLOW $sourceId n=$frameCount")
        }
        return true
    }

    /** True once at least one frame has been consumed (layers draw nothing before this). */
    override fun hasContent(): Boolean = hasFrame

    override fun release() {
        surface.release()
        surfaceTexture.setOnFrameAvailableListener(null)
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(glTextureId), 0)
    }

    private fun glGenOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )
        return ids[0]
    }
}
