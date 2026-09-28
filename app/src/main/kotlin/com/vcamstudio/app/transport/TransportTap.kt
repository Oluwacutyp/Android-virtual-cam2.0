package com.vcamstudio.app.transport

import java.nio.ByteBuffer

/**
 * r53: the single tap point from the EXISTING analyzer stream into the
 * transport ring. [AiFrameAnalyzer] calls [dispatch] after its I420
 * compaction; the copy happens here so the AI path is untouched and never
 * blocked by transport.
 */
object TransportTap {

    @Volatile private var enabled = false

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) TransportManager.enableFeed() else TransportManager.disableFeed()
    }

    fun isEnabled(): Boolean = enabled

    fun dispatch(payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long) {
        if (!enabled) return
        runCatching {
            val dup = payload.duplicate()
            dup.position(0)
            TransportManager.onFrame(dup, width, height, rotationDeg, frameId)
        }
    }
}
