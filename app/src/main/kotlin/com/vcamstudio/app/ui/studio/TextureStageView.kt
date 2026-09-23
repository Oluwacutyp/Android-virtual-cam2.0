package com.vcamstudio.app.ui.studio

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView

/**
 * GL-compositor stage backed by a [TextureView]: unlike SurfaceView it lives
 * INSIDE the view hierarchy, so it can never be occluded, z-order-fought, or
 * punched through wrongly by Compose content — the entire black-but-swapping
 * SurfaceView class of failures is structurally impossible here.
 */
class TextureStageView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    var onSurfaceReady: ((Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: (() -> Unit)? = null
    var onTap: ((x: Float, y: Float, viewW: Float, viewH: Float) -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
        isClickable = true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        onSurfaceReady?.invoke(Surface(st), width, height)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // Re-attach with the fresh size (engine replaces the output wholesale).
        onSurfaceReady?.invoke(Surface(st), width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        onSurfaceGone?.invoke()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onTap?.invoke(event.x, event.y, width.toFloat(), height.toFloat())
            return true
        }
        return super.onTouchEvent(event)
    }
}
