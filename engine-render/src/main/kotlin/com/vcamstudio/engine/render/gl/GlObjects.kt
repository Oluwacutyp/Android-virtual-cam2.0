package com.vcamstudio.engine.render.gl

import android.opengl.GLES30
import java.util.ArrayDeque

/** A 2D GL texture with explicit size. */
class GlTexture(val width: Int, val height: Int, target: Int = GLES30.GL_TEXTURE_2D) {

    val id: Int = genTexture()
    val target: Int = target

    private fun genTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        if (ids[0] == 0) throw GlException("glGenTextures failed")
        return ids[0]
    }

    /** Allocates RGBA8 storage (2D only). */
    fun allocateRgba() {
        bind()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        setDefaultParams()
        checkGlError("GlTexture.allocateRgba($width x $height)")
    }

    fun setDefaultParams() {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun bind() = GLES30.glBindTexture(target, id)

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)
}

/** Offscreen render target (RGBA8 color texture). */
class Framebuffer(val width: Int, val height: Int) {

    val texture: GlTexture = GlTexture(width, height).also { it.allocateRgba() }
    val handle: Int = genFbo()

    init {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, handle)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture.id, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            texture.release()
            GLES30.glDeleteFramebuffers(1, intArrayOf(handle), 0)
            throw GlException("Framebuffer incomplete: 0x${Integer.toHexString(status)} (${width}x$height)")
        }
    }

    private fun genFbo(): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        if (ids[0] == 0) throw GlException("glGenFramebuffers failed")
        return ids[0]
    }

    /** Binds and sets the viewport to the full FBO. */
    fun bindViewport() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, handle)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        texture.release()
        GLES30.glDeleteFramebuffers(1, intArrayOf(handle), 0)
    }
}

/** Recycles [Framebuffer]s of equal size across frames (no per-frame allocation). */
class FboPool {

    private val pool = ArrayDeque<Framebuffer>()

    fun acquire(width: Int, height: Int): Framebuffer {
        while (pool.isNotEmpty()) {
            val fbo = pool.removeFirst()
            if (fbo.width == width && fbo.height == height) return fbo
            fbo.release() // wrong size -> discard
        }
        return Framebuffer(width, height)
    }

    fun release(fbo: Framebuffer) {
        pool.addLast(fbo)
        while (pool.size > MAX_POOL) pool.removeFirst().release()
    }

    fun clear() {
        while (pool.isNotEmpty()) pool.removeFirst().release()
    }

    companion object {
        private const val MAX_POOL = 6
    }
}
