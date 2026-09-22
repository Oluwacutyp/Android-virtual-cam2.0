package com.vcamstudio.engine.render.model

/**
 * 2D transform of a layer inside the scene, in normalized scene coordinates
 * (0..1 relative to scene width/height) so scenes are resolution-independent.
 */
data class LayerTransform(
    /** Center X in scene-normalized units (0.5 = center). */
    val centerX: Float = 0.5f,
    /** Center Y in scene-normalized units (0.5 = center). */
    val centerY: Float = 0.5f,
    /** Box width as a fraction of scene width. */
    val width: Float = 1f,
    /** Box height as a fraction of scene height. */
    val height: Float = 1f,
    /** Rotation in degrees, clockwise. */
    val rotationDeg: Float = 0f,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val fitMode: FitMode = FitMode.FILL,
    /** Corner radius as a fraction of the smaller displayed dimension (0..0.5). */
    val cornerRadius: Float = 0f,
) {
    companion object {
        val DEFAULT = LayerTransform()
    }
}
