package com.vcamstudio.engine.render.model

/** Per-layer color correction. All values default to neutral. */
data class ColorGrade(
    /** -1 (black) .. +1 (white) additive. */
    val brightness: Float = 0f,
    /** 0 .. 2, 1 = neutral (contrast around mid-gray). */
    val contrast: Float = 1f,
    /** 0 .. 3, 1 = neutral. */
    val saturation: Float = 1f,
    /** 0.2 .. 4, 1 = neutral. */
    val gamma: Float = 1f,
    /** -1 (cool) .. +1 (warm). */
    val temperature: Float = 0f,
    /** -1 (magenta) .. +1 (green). */
    val tint: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f &&
            gamma == 1f && temperature == 0f && tint == 0f
}

/** Gaussian blur radius in scene pixels (0 = off). Two-pass separable. */
data class BlurFx(val radius: Float = 0f) {
    val isEnabled: Boolean get() = radius > 0.5f
}

/** 3x3 unsharp-mask amount (0 = off). Applied to 2D-textured layers. */
data class SharpenFx(val amount: Float = 0f) {
    val isEnabled: Boolean get() = amount > 0.01f
}

/** Radial vignette in quad-local distance units (1.0 = corner distance). */
data class VignetteFx(
    val start: Float = 0.55f,
    val end: Float = 1.05f,
    val strength: Float = 0f,
) {
    val isEnabled: Boolean get() = strength > 0.01f
}

/** Complete effect chain of a layer. */
data class LayerEffects(
    val colorGrade: ColorGrade = ColorGrade(),
    val blur: BlurFx = BlurFx(),
    val sharpen: SharpenFx = SharpenFx(),
    val vignette: VignetteFx = VignetteFx(),
    /** Named 3D LUT (see RenderEngine.registerLut); null = no LUT. */
    val lutId: String? = null,
) {
    val isNeutral: Boolean
        get() = colorGrade.isNeutral && !blur.isEnabled && !sharpen.isEnabled &&
            !vignette.isEnabled && lutId == null
}
