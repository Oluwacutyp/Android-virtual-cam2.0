package com.vcamstudio.engine.render.gl

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * One render target backed by a [Surface] (stage SurfaceView, future encoder
 * input, ...). All failures mark the surface invalid instead of crashing — the
 * watchdog observes validity and re-initializes.
 */
class EglWindowSurface(
    private val egl: EglCore,
    surface: Surface,
) {
    private var eglSurface: EGLSurface = egl.createWindowSurface(surface)

    var isValid: Boolean = true
        private set

    fun makeCurrent(): Boolean =
        if (isValid && egl.makeCurrent(eglSurface)) {
            true
        } else {
            isValid = false
            false
        }

    fun swap(): Boolean =
        if (isValid && EGL14.eglSwapBuffers(egl.eglDisplay, eglSurface)) {
            true
        } else {
            isValid = false
            false
        }

    /**
     * Round 24: EGL_SWAP_BEHAVIOR of this window surface — TRUE if buffer
     * content is preserved across swap. On non-preserved surfaces a
     * post-swap glReadPixels reads the RECYCLED (undefined-content) back
     * buffer, so PRESENT_PROBE interpretations must check this first.
     */
    fun swapBehaviorPreserved(): Boolean? =
        if (!isValid) null else {
            val v = IntArray(1)
            if (EGL14.eglQuerySurface(egl.eglDisplay, eglSurface, EGL14.EGL_SWAP_BEHAVIOR, v, 0)) {
                v[0] == EGL14.EGL_BUFFER_PRESERVED
            } else null
        }

    fun setPresentationTime(nanos: Long) {
        if (isValid) {
            EGLExt.eglPresentationTimeANDROID(egl.eglDisplay, eglSurface, nanos)
        }
    }

    fun release() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                egl.destroySurface(eglSurface)
            } catch (_: Throwable) {
                // Surface already dead (window destroyed) — nothing to do.
            }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        isValid = false
    }
}
