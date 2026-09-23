package com.vcamstudio.engine.render.geometry

import com.vcamstudio.engine.render.model.FitMode
import com.vcamstudio.engine.render.model.LayerTransform
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure (non-GL, non-Android) layer placement math — unit tested.
 *
 * Produces the on-screen quad (in scene pixel space) and the source-UV window
 * for a layer transform, honoring fit mode, mirror and rotation. The render
 * thread converts scene pixels to clip space with a simple Y flip.
 */
object LayerGeometry {

    /** Corner order: TL, TR, BR, BL. */
    data class Quad(
        val cornersPx: FloatArray, // 8 floats: x0,y0,x1,y1,x2,y2,x3,y3
        val uvs: FloatArray,       // 8 floats matching corners
    ) {
        override fun equals(other: Any?): Boolean =
            other is Quad && cornersPx.contentEquals(other.cornersPx) && uvs.contentEquals(other.uvs)

        override fun hashCode(): Int = cornersPx.contentHashCode() * 31 + uvs.contentHashCode()
    }

    private val CORNERS = arrayOf(
        -0.5f to -0.5f, // TL
        0.5f to -0.5f,  // TR
        0.5f to 0.5f,   // BR
        -0.5f to 0.5f,  // BL
    )

    /**
     * @param sourceWidthPx  current texture/buffer width (drives aspect)
     * @param sourceHeightPx current texture/buffer height
     */
    fun compute(
        transform: LayerTransform,
        sourceWidthPx: Float,
        sourceHeightPx: Float,
        sceneWidthPx: Float,
        sceneHeightPx: Float,
    ): Quad {
        val boxW = (transform.width.coerceIn(0.01f, 4f)) * sceneWidthPx
        val boxH = (transform.height.coerceIn(0.01f, 4f)) * sceneHeightPx
        val cx = transform.centerX * sceneWidthPx
        val cy = transform.centerY * sceneHeightPx

        val sw = sourceWidthPx.coerceAtLeast(1f)
        val sh = sourceHeightPx.coerceAtLeast(1f)
        val sourceAspect = sw / sh

        var drawW: Float
        var drawH: Float
        var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f

        when (transform.fitMode) {
            FitMode.STRETCH -> {
                drawW = boxW; drawH = boxH
            }
            FitMode.FIT -> {
                val s = min(boxW / sw, boxH / sh)
                drawW = sw * s; drawH = sh * s
            }
            FitMode.FILL -> {
                val s = max(boxW / sw, boxH / sh)
                drawW = sw * s; drawH = sh * s
                // Visible fraction of the scaled source -> crop the UV window.
                val fx = min(1f, boxW / drawW)
                val fy = min(1f, boxH / drawH)
                u0 = (1f - fx) / 2f; u1 = 1f - u0
                v0 = (1f - fy) / 2f; v1 = 1f - v0
                drawW = boxW; drawH = boxH
            }
        }

        // Guard degenerate sizes.
        if (drawW <= 0f) drawW = 1f
        if (drawH <= 0f) drawH = 1f

        val rad = Math.toRadians(transform.rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val corners = FloatArray(8)
        val uvs = FloatArray(8)
        val cornerU = floatArrayOf(u0, u1, u1, u0)
        val cornerV = floatArrayOf(v0, v0, v1, v1)

        for (i in 0 until 4) {
            val (lx, ly) = CORNERS[i]
            // rotate local corner, scale to displayed size, translate to center
            val rx = lx * c - ly * s
            val ry = lx * s + ly * c
            corners[i * 2] = cx + rx * drawW
            corners[i * 2 + 1] = cy + ry * drawH

            var u = cornerU[i]
            var v = cornerV[i]
            if (transform.mirrorX) u = (u0 + u1) - u
            if (transform.mirrorY) v = (v0 + v1) - v
            // Producer-stored rotation (camera sensor / video metadata):
            // rotate the sampling window around the UV center so the content
            // reads upright regardless of buffer orientation.
            if (transform.uvRotationDeg != 0f) {
                val ru = u - 0.5f
                val rv = v - 0.5f
                val rad = Math.toRadians(transform.uvRotationDeg.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                u = 0.5f + ru * c - rv * s
                v = 0.5f + ru * s + rv * c
            }
            uvs[i * 2] = u
            uvs[i * 2 + 1] = v
        }
        return Quad(corners, uvs)
    }

    /**
     * Converts a scene-pixel corner pair to clip space with a Y flip
     * (scene Y grows downward, GL clip Y grows upward).
     */
    fun toClipSpace(xPx: Float, yPx: Float, sceneWidthPx: Float, sceneHeightPx: Float): FloatArray {
        val x = xPx / sceneWidthPx * 2f - 1f
        val y = 1f - yPx / sceneHeightPx * 2f
        return floatArrayOf(x, y)
    }

    /**
     * Corner radius in display pixels for the fragment-shader rounded-rect SDF
     * (the shader scales quad-local coords by these pixel sizes, so the radius
     * stays visually uniform regardless of aspect).
     */
    fun cornerRadiusPx(radiusFraction: Float, drawW: Float, drawH: Float): Float =
        (radiusFraction.coerceIn(0f, 0.5f)) * min(drawW, drawH).coerceAtLeast(0f)
}
