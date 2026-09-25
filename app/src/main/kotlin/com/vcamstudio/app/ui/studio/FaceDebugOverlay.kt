package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.vcamstudio.engine.aiface.FaceBox
import com.vcamstudio.engine.aiface.FaceDetectionController
import com.vcamstudio.engine.render.model.LayerDefinition

private val SCRFD_CYAN = Color(0xFF22D3EE)

/**
 * Phase 2 (owner DELIVERABLE 2): the debug overlay. Draws a cyan rectangle
 * + confidence for the top detection, or a "Model missing"/"session failed"
 * badge when detection cannot run. Pure Compose — the render engine is not
 * touched (the box is mapped through the same geometry the engine uses:
 * frame -> camera layer quad (fill) -> scene -> stage view (fill-crop)).
 */
@Composable
fun FaceDebugOverlay(
    box: FaceBox?,
    phase: FaceDetectionController.Phase,
    rawMode: Boolean,
    scene: com.vcamstudio.engine.render.model.SceneDefinition?,
    sceneRes: com.vcamstudio.app.settings.SceneResolution,
    modifier: Modifier = Modifier,
) {
    val hasCamLayer = scene?.layers?.any { it is LayerDefinition.Camera } == true
    if (!hasCamLayer) return

    if (phase == FaceDetectionController.Phase.MODEL_MISSING ||
        phase == FaceDetectionController.Phase.SESSION_FAILED
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                if (phase == FaceDetectionController.Phase.MODEL_MISSING) {
                    "SCRFD: Model missing"
                } else {
                    "SCRFD: session failed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = SCRFD_CYAN,
            )
        }
    }

    if (box == null || box.frameWidth <= 0 || box.frameHeight <= 0) return
    Canvas(modifier.fillMaxSize()) {
        val viewW = size.width
        val viewH = size.height

        val rect: androidx.compose.ui.geometry.Rect = if (rawMode) {
            // RAW: the upright frame IS the stage content (PreviewView
            // FILL_CENTER) — fill-crop frame -> view.
            mapNormalizedFillCrop(box, box.frameWidth.toFloat(), box.frameHeight.toFloat(), 0f, 0f, viewW, viewH)
        } else {
            // GL: frame -> camera layer quad -> scene px -> view.
            val cam = scene?.layers?.filterIsInstance<LayerDefinition.Camera>()?.firstOrNull() ?: return@Canvas
            val t = cam.transform
            val sceneW = sceneRes.width.toFloat()
            val sceneH = sceneRes.height.toFloat()
            val layerX = t.centerX * sceneW
            val layerY = t.centerY * sceneH
            val layerW = t.width * sceneW
            val layerH = t.height * sceneH
            val inLayer = mapNormalizedFillCrop(
                box, box.frameWidth.toFloat(), box.frameHeight.toFloat(),
                layerX - layerW / 2f, layerY - layerH / 2f, layerW, layerH,
            )
            // scene px -> view (engine fill-crops the scene into the stage)
            val s = maxOf(viewW / sceneW, viewH / sceneH)
            val offX = (viewW - sceneW * s) / 2f
            val offY = (viewH - sceneH * s) / 2f
            androidx.compose.ui.geometry.Rect(
                offX + inLayer.left * s,
                offY + inLayer.top * s,
                offX + inLayer.right * s,
                offY + inLayer.bottom * s,
            )
        }

        drawRoundRect(
            color = SCRFD_CYAN,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            style = Stroke(width = 5f),
        )
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(0x22, 0xD3, 0xEE)
                textSize = 36f
                isAntiAlias = true
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            }
            canvas.nativeCanvas.drawText(
                "%.0f%%".format(box.score * 100),
                rect.left,
                (rect.top - 12f).coerceAtLeast(36f),
                paint,
            )
        }
    }
}

/** Fill-crop (CameraX FILL_CENTER / engine fit) of a normalized box from src into dst px. */
private fun mapNormalizedFillCrop(
    box: FaceBox,
    srcW: Float,
    srcH: Float,
    dstX: Float,
    dstY: Float,
    dstW: Float,
    dstH: Float,
): androidx.compose.ui.geometry.Rect {
    val scale = maxOf(dstW / srcW, dstH / srcH)
    val offX = (dstW - srcW * scale) / 2f
    val offY = (dstH - srcH * scale) / 2f
    return androidx.compose.ui.geometry.Rect(
        dstX + offX + box.x1 * srcW * scale,
        dstY + offY + box.y1 * srcH * scale,
        dstX + offX + box.x2 * srcW * scale,
        dstY + offY + box.y2 * srcH * scale,
    )
}
