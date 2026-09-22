package com.vcamstudio.engine.render.gl

import android.opengl.GLES30

/** Fatal GL/EGL problem with human-readable context. Never silently swallowed. */
class GlException(message: String) : RuntimeException(message)

/** Throws [GlException] if a GL error is pending. Call after significant GL calls. */
fun checkGlError(where: String) {
    val err = GLES30.glGetError()
    if (err != 0) throw GlException("GL error 0x${Integer.toHexString(err)} at $where")
}

/** Maps common EGL error codes to readable text for diagnostics. */
fun eglErrorName(code: Int): String = when (code) {
    0x3000 -> "EGL_SUCCESS"
    0x3001 -> "EGL_NOT_INITIALIZED"
    0x3002 -> "EGL_BAD_ACCESS"
    0x3003 -> "EGL_BAD_ALLOC"
    0x3004 -> "EGL_BAD_ATTRIBUTE"
    0x3005 -> "EGL_BAD_CONFIG"
    0x3006 -> "EGL_BAD_CONTEXT"
    0x3007 -> "EGL_BAD_CURRENT_SURFACE"
    0x3008 -> "EGL_BAD_DISPLAY"
    0x3009 -> "EGL_BAD_MATCH"
    0x300A -> "EGL_BAD_NATIVE_PIXMAP"
    0x300B -> "EGL_BAD_NATIVE_WINDOW"
    0x300C -> "EGL_BAD_PARAMETER"
    0x300D -> "EGL_BAD_SURFACE"
    0x300E -> "EGL_CONTEXT_LOST"
    else -> "EGL_0x${Integer.toHexString(code)}"
}
