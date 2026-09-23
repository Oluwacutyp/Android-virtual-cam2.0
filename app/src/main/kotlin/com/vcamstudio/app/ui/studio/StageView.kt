package com.vcamstudio.app.ui.studio

import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Plain SurfaceView the engine renders into. The engine is app-scoped, so
 * surfaceCreated/Destroyed simply attach/detach this output — pipeline keeps
 * running underneath (never-black design).
 *
 * Tap-to-focus is forwarded with raw view coordinates.
 */
class StageView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var onSurfaceReady: ((Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: (() -> Unit)? = null
    var onTap: ((x: Float, y: Float, viewW: Float, viewH: Float) -> Unit)? = null

    init {
        // The engine composites onto this surface; keep it above the window so
        // Compose backgrounds can never occlude it (deterministic never-black).
        setZOrderOnTop(true)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        onSurfaceReady?.invoke(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceGone?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onTap?.invoke(event.x, event.y, width.toFloat(), height.toFloat())
            return true
        }
        return super.onTouchEvent(event)
    }
}
