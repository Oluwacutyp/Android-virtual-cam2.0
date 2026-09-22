package com.vcamstudio.engine.render.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU-side registry of named 3D LUT textures (GL_TEXTURE_3D, RGB16F, linear).
 * RGB16F is core-filterable in ES 3.0 (32F is not without extensions).
 * Render-thread only (EGL context must be current).
 */
class LutCache {

    private val textures = HashMap<String, Int>()

    fun get(name: String): Int? = textures[name]

    /** Uploads [rgb] (size^3 * 3 floats, x=red fastest) as a 3D texture. */
    fun put(name: String, size: Int, rgb: FloatArray) {
        require(rgb.size == size * size * size * 3) { "LUT data size mismatch" }
        remove(name)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val buf = ByteBuffer.allocateDirect(rgb.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(rgb)
        buf.position(0)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            size, size, size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf,
        )
        textures[name] = tex[0]
    }

    fun remove(name: String) {
        textures.remove(name)?.let { id ->
            GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        }
    }

    fun clear() {
        for (id in textures.values) {
            GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        }
        textures.clear()
    }
}
