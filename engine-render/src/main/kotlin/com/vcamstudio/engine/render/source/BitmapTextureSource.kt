package com.vcamstudio.engine.render.source

import android.graphics.Bitmap
import android.opengl.GLUtils
import android.opengl.GLES30

/**
 * 2D texture fed from a [Bitmap] (imported images, Canvas-rasterized text).
 * Bitmap swaps are staged from any thread and uploaded on the render thread.
 */
class BitmapTextureSource(
    override val sourceId: String,
) : TextureSource {

    override var width: Int = 1
        private set
    override var height: Int = 1
        private set

    override val target: Int = GLES30.GL_TEXTURE_2D

    override val glTextureId: Int = glGenTexture()
    @Volatile
    private var pending: Bitmap? = null
    private var hasContentFlag = false

    /** Thread-safe: stages a bitmap for upload on the next render tick. */
    fun setBitmap(bitmap: Bitmap) {
        pending = bitmap
    }

    override fun update(): Boolean {
        val bmp = pending ?: return false
        pending = null
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTextureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        width = bmp.width
        height = bmp.height
        hasContentFlag = true
        return true
    }

    override fun hasContent(): Boolean = hasContentFlag

    override fun release() {
        GLES30.glDeleteTextures(1, intArrayOf(glTextureId), 0)
    }

    private fun glGenTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }
}
