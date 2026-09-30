package com.vcamstudio.app.ui.studio

/**
 * r64.1: pure placement math for the live-swap scene layer, extracted from
 * StudioViewModel so it can be unit-tested (same precedent as
 * FaceOverlayMath). No Android or engine imports — plain floats in, patch
 * rect out.
 *
 * Coordinate chain, all verified against the shipped r61-r63 overlay:
 *  - boxNorm is the face box in the RAW UPRIGHT analyzer frame (0..1);
 *  - the STAGE camera layer renders the upright frame fill-cropped into the
 *    layer quad, and MIRRORED horizontally when the layer's mirrorX is set
 *    (front cam — StOrientation: mirrorX = mirror XOR isFront, applied to
 *    the layer UVs by SourceUvMath);
 *  - therefore the patch POSITION must use the mirrored box x (the visible
 *    face sits at 1-x), and the patch TEXTURE must be flipped exactly once
 *    (layer mirrorX) so its content matches the mirrored surroundings.
 *    r61-r63 flipped the texture but placed the box at raw x — part of the
 *    offset seen in the r63 screenshots.
 *
 * The patch itself is a SQUARE: the inswapper output is an aligned 128x128
 * crop whose face fills the frame, so it is grown to max(box side) * grow
 * and drawn with FitMode.FILL (square into square = exact 1:1, no crop).
 */
internal object SwapPatchMath {

    /** Scene-normalized rect + texture flip for the patch layer. */
    data class Patch(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val mirrorX: Boolean,
    )

    /**
     * Map [boxNorm] (raw upright frame coords) to a scene-normalized square
     * patch on the camera layer quad. Returns null for degenerate input —
     * callers just skip the frame, never throw.
     *
     * @param grow patch size multiplier over the longer box side (1.25f).
     */
    fun patch(
        boxNorm: FloatArray,
        frameW: Int,
        frameH: Int,
        camCenterX: Float,
        camCenterY: Float,
        camWidth: Float,
        camHeight: Float,
        camMirrorX: Boolean,
        sceneW: Int,
        sceneH: Int,
        grow: Float = 1.25f,
    ): Patch? {
        if (boxNorm.size < 4) return null
        if (frameW <= 0 || frameH <= 0 || sceneW <= 0 || sceneH <= 0) return null
        val layerW = camWidth * sceneW
        val layerH = camHeight * sceneH
        if (layerW <= 0f || layerH <= 0f) return null

        // Mirror the box x exactly once when the stage content is mirrored
        // (x' = 1 - x2, x2' = 1 - x1 — same rule as FaceOverlayMath.mirrorX).
        var bx1 = boxNorm[0]
        var bx2 = boxNorm[2]
        if (camMirrorX) {
            val n1 = 1f - bx2
            bx2 = 1f - bx1
            bx1 = n1
        }

        // Frame -> layer quad fill-crop (identical formula to the overlay's
        // mapNormalizedFillCrop and the engine's fit math).
        val scale = maxOf(layerW / frameW, layerH / frameH)
        val offX = camCenterX * sceneW - layerW / 2f + (layerW - frameW * scale) / 2f
        val offY = camCenterY * sceneH - layerH / 2f + (layerH - frameH * scale) / 2f
        val x1 = offX + bx1 * frameW * scale
        val y1 = offY + boxNorm[1] * frameH * scale
        val x2 = offX + bx2 * frameW * scale
        val y2 = offY + boxNorm[3] * frameH * scale

        val size = maxOf(x2 - x1, y2 - y1) * grow
        if (size <= 0f) return null
        return Patch(
            centerX = ((x1 + x2) / 2f) / sceneW,
            centerY = ((y1 + y2) / 2f) / sceneH,
            width = size / sceneW,
            height = size / sceneH,
            mirrorX = camMirrorX,
        )
    }
}
