package com.vcamstudio.engine.render.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.vcamstudio.engine.render.model.TextAlignment
import com.vcamstudio.engine.render.model.TextSpec
import kotlin.math.ceil
import kotlin.math.min

/**
 * Canvas bridge: text layers are rasterized by Android's text engine into a
 * bitmap (correct fonts, emoji, shaping — by construction) and uploaded as a
 * GL texture. No text shaders, no font problems.
 */
object TextRasterizer {

    private const val MAX_TEX = 2048

    fun rasterize(spec: TextSpec, sceneWidthPx: Int, sceneHeightPx: Int): Bitmap {
        val sizePx = (spec.sizeFraction.coerceIn(0.01f, 0.5f)) * sceneHeightPx
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = sizePx
            this.color = spec.colorArgb.toInt()
            this.isFakeBoldText = spec.bold
            this.textAlign = when (spec.alignment) {
                TextAlignment.LEFT -> Paint.Align.LEFT
                TextAlignment.CENTER -> Paint.Align.CENTER
                TextAlignment.RIGHT -> Paint.Align.RIGHT
            }
        }

        val lines = spec.text.split("\n").ifEmpty { listOf(" ") }
        val lineSpacing = sizePx * 0.25f
        val maxWidth = min(sceneWidthPx * 0.92f, MAX_TEX.toFloat())

        var widest = 0f
        for (line in lines) widest = maxOf(widest, paint.measureText(line))
        val textW = min(widest, maxWidth)
        val textH = lines.size * sizePx + (lines.size - 1) * lineSpacing

        val padX = sizePx * 0.35f
        val padY = sizePx * 0.25f
        val boxW = min(ceil(textW + padX * 2), MAX_TEX.toFloat()).toInt().coerceAtLeast(2)
        val boxH = min(ceil(textH + padY * 2), MAX_TEX.toFloat()).toInt().coerceAtLeast(2)

        val bitmap = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (spec.backgroundArgb != 0L) {
            val bg = Paint().apply { this.color = spec.backgroundArgb.toInt() }
            canvas.drawRoundRect(
                RectF(0f, 0f, boxW.toFloat(), boxH.toFloat()),
                sizePx * 0.3f, sizePx * 0.3f, bg,
            )
        }

        val anchorX = when (spec.alignment) {
            TextAlignment.LEFT -> padX
            TextAlignment.CENTER -> boxW / 2f
            TextAlignment.RIGHT -> boxW - padX
        }
        var baseline = padY + sizePx * 0.85f
        for (line in lines) {
            drawFitted(line, anchorX, baseline, maxWidth, paint)
            baseline += sizePx + lineSpacing
        }
        return bitmap
    }

    /** Draws a line, horizontally squeezing it if it exceeds [maxWidth]. */
    private fun Canvas.drawFitted(line: String, x: Float, baseline: Float, maxWidth: Float, paint: Paint) {
        val measured = paint.measureText(line)
        if (measured <= maxWidth || measured == 0f) {
            drawText(line, x, baseline, paint)
            return
        }
        val save = save()
        scale(maxWidth / measured, 1f, x, baseline - paint.textSize / 2f)
        drawText(line, x, baseline, paint)
        restoreToCount(save)
    }
}
