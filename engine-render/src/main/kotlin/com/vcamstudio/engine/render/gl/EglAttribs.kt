package com.vcamstudio.engine.render.gl

import android.opengl.EGL14

/**
 * Builds EGL attribute lists. Every EGL call that takes an attrib_list
 * REQUIRES a trailing EGL_NONE — a malformed list is rejected by drivers
 * ("attrib_list must contain EGL_NONE!") and some fail silently. Always build
 * lists through [flatten]; never hand-roll IntArrays.
 */
object EglAttribs {

    /** The EGL_NONE terminator value (0x3038, per EGL spec). */
    const val NONE: Int = EGL14.EGL_NONE

    /**
     * Interleaves key/value pairs and appends the EGL_NONE terminator.
     *
     * Regression guard: the original hand-rolled flattening allocated
     * `pairs.size` ints (half the needed length) and mis-indexed odd slots,
     * producing a garbled, unterminated list — the root cause of the
     * device-gate "black preview / 0 fps" failure.
     */
    fun flatten(pairs: List<Pair<Int, Int>>): IntArray {
        val out = IntArray(pairs.size * 2 + 1)
        for (i in pairs.indices) {
            out[2 * i] = pairs[i].first
            out[2 * i + 1] = pairs[i].second
        }
        out[pairs.size * 2] = NONE
        return out
    }
}
