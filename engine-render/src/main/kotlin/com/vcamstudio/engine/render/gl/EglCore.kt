package com.vcamstudio.engine.render.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.Surface

/**
 * Owns the single EGL display/context for the whole render engine (blueprint §B.3:
 * one context, one render thread — GLSurfaceView is banned).
 *
 * Every setup stage logs under `EGL_STAGE <stage>` (tag vcam-render) so a device
 * logcat answers exactly where initialization broke: display → init → config
 * (4 fallback chains) → context → pbuffer → current.
 */
class EglCore {

    private companion object {
        const val TAG = "vcam-render"

        // android EGL14 lacks the ES3 constant on older API levels; 0x0040 per EGL spec.
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    val glRenderer: String
    val glVersion: String
    val eglApiVersion: String

    init {
        Log.i(TAG, "EGL_STAGE display: requesting default display")
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw GlException("EGL_STAGE display: eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw GlException("EGL_STAGE init: eglInitialize failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        eglApiVersion = "${version[0]}.${version[1]}"
        Log.i(TAG, "EGL_STAGE init OK egl=$eglApiVersion")

        config = chooseConfig()
            ?: throw GlException("EGL_STAGE config: all 4 config chains rejected")
        val visualId = IntArray(1)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_NATIVE_VISUAL_ID, visualId, 0)
        Log.i(TAG, "EGL_STAGE chosenConfig nativeVisualId=${visualId[0]}")

        val attribs = EglAttribs.flatten(listOf(EGL14.EGL_CONTEXT_CLIENT_VERSION to 3))
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw GlException("EGL_STAGE context: create failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        Log.i(TAG, "EGL_STAGE context OK (client v3)")

        // 1x1 pbuffer keeps the context current so GL objects (textures, FBOs,
        // programs) can be created before any output surface exists.
        val pattribs = EglAttribs.flatten(listOf(EGL14.EGL_WIDTH to 1, EGL14.EGL_HEIGHT to 1))
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, pattribs, 0)
        if (pbuffer == EGL14.EGL_NO_SURFACE) {
            throw GlException("EGL_STAGE pbuffer: create failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        makeCurrentPbuffer()
        Log.i(TAG, "EGL_STAGE pbuffer OK + current")

        glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
        glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
        Log.i(TAG, "EGL_STAGE ready renderer=$glRenderer gl=$glVersion")
    }

    /**
     * Config chooser with an explicit fallback ladder; the active chain is
     * logged. ES3 contexts need an ES3-capable config on strict drivers, but
     * lenient drivers accept an ES3 context on an ES2 config — so both are
     * tried, recordable-first (MediaCodec surface input needs it later).
     */
    private fun chooseConfig(): EGLConfig? {
        val pixel = listOf(
            EGL14.EGL_RED_SIZE to 8,
            EGL14.EGL_GREEN_SIZE to 8,
            EGL14.EGL_BLUE_SIZE to 8,
            EGL14.EGL_ALPHA_SIZE to 8,
            EGL14.EGL_DEPTH_SIZE to 0,
            EGL14.EGL_STENCIL_SIZE to 0,
        )
        val surface = listOf(
            EGL14.EGL_SURFACE_TYPE to (EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT),
        )
        val es3 = listOf(EGL14.EGL_RENDERABLE_TYPE to (EGL_OPENGL_ES3_BIT or EGL14.EGL_OPENGL_ES2_BIT))
        val es2 = listOf(EGL14.EGL_RENDERABLE_TYPE to EGL14.EGL_OPENGL_ES2_BIT)
        val recordable = listOf(EGLExt.EGL_RECORDABLE_ANDROID to 1)

        val chains = listOf(
            "recordable+es3" to (es3 + surface + pixel + recordable),
            "plain+es3" to (es3 + surface + pixel),
            "recordable+es2" to (es2 + surface + pixel + recordable),
            "plain+es2" to (es2 + surface + pixel),
        )
        for ((name, attribs) in chains) {
            val cfg = findConfig(attribs)
            if (cfg != null) {
                Log.i(TAG, "EGL_STAGE config OK chain=$name")
                return cfg
            }
            Log.w(TAG, "EGL_STAGE config chain=$name rejected")
        }
        return null
    }

    private fun findConfig(attribs: List<Pair<Int, Int>>): EGLConfig? {
        // ROOT-CAUSE FIX (device gate rounds 1-3): the previous hand-rolled
        // flattening allocated attribs.size ints (HALF the needed length) and
        // mis-indexed odd slots, so eglChooseConfig received a garbled list
        // without the EGL_NONE terminator ("attrib_list must contain
        // EGL_NONE!"). All lists are now built by the tested EglAttribs.
        val flat = EglAttribs.flatten(attribs)
        val numConfigs = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(display, flat, 0, configs, 0, 1, numConfigs, 0)) return null
        return if (numConfigs[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, EglAttribs.flatten(emptyList()), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw GlException("EGL_STAGE window-surface: create failed: ${eglErrorName(EGL14.eglGetError())}")
        }
        return s
    }

    /** Raw display handle, needed for per-surface presentation time calls. */
    val eglDisplay: EGLDisplay get() = display

    fun makeCurrent(eglSurface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

    fun makeCurrentPbuffer() {
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            throw GlException("EGL_STAGE pbuffer-current: failed: ${eglErrorName(EGL14.eglGetError())}")
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
}
