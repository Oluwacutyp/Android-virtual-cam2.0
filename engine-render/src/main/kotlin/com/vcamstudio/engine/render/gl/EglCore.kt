package com.vcamstudio.engine.render.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface

/**
 * Owns the single EGL display/context for the whole render engine (blueprint §B.3:
 * one context, one render thread — GLSurfaceView is banned).
 *
 * The context is created once per engine lifetime and survives Activity
 * recreation; output surfaces attach and detach without touching GL objects.
 */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    val glRenderer: String
    val glVersion: String
    val eglApiVersion: String

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw GlException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw GlException("eglInitialize failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        eglApiVersion = "${version[0]}.${version[1]}"

        config = chooseConfig()
            ?: throw GlException("No suitable EGL config (tried RECORDABLE and plain)")
        val attribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
        checkEgl("eglCreateContext")
        if (context == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext returned NO_CONTEXT")

        // 1x1 pbuffer keeps the context current so GL objects (textures, FBOs,
        // programs) can be created before any output surface exists.
        val pattribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, pattribs, 0)
        checkEgl("eglCreatePbufferSurface")
        makeCurrentPbuffer()

        glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
        glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
    }

    /**
     * Config chooser with a deliberate fallback chain: pixel format + RECORDABLE
     * (needed later for MediaCodec encoder input surfaces) first, then without
     * RECORDABLE for devices whose window formats reject the flag.
     */
    private fun chooseConfig(): EGLConfig? {
        val base = mutableListOf(
            EGL14.EGL_RENDERABLE_TYPE to EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE to (EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT),
            EGL14.EGL_RED_SIZE to 8,
            EGL14.EGL_GREEN_SIZE to 8,
            EGL14.EGL_BLUE_SIZE to 8,
            EGL14.EGL_ALPHA_SIZE to 8,
            EGL14.EGL_DEPTH_SIZE to 0,
            EGL14.EGL_STENCIL_SIZE to 0,
        )
        val withRecordable = base + listOf(EGLExt.EGL_RECORDABLE_ANDROID to 1, EGL14.EGL_NONE to 0)
        val withoutRecordable = base + listOf(EGL14.EGL_NONE to 0)

        return findConfig(withRecordable) ?: findConfig(withoutRecordable)
    }

    private fun findConfig(attribs: List<Pair<Int, Int>>): EGLConfig? {
        val flat = IntArray(attribs.size) { i ->
            if (i % 2 == 0) attribs[i].first else attribs[i].second
        }
        val numConfigs = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(display, flat, 0, configs, 0, 1, numConfigs, 0)) return null
        return if (numConfigs[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw GlException("eglCreateWindowSurface failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        return s
    }

    /** Raw display handle, needed for per-surface presentation time calls. */
    val eglDisplay: EGLDisplay get() = display


    fun makeCurrent(eglSurface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

    fun makeCurrentPbuffer() {
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            throw GlException("eglMakeCurrent(pbuffer) failed: ${eglErrorName(EGL14.eglGetError())}")
        }
    }

    fun destroySurface(eglSurface: EGLSurface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, eglSurface)
        }
    }

    /** Releases context + display. The engine object is dead after this. */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    private fun checkEgl(where: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) {
            throw GlException("$where failed: ${eglErrorName(err)}")
        }
    }
}
