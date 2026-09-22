package com.vcamstudio.engine.render.model

/** Which physical camera a camera layer binds to. */
enum class LensFacing { FRONT, BACK }

enum class TextAlignment { LEFT, CENTER, RIGHT }

/** Style + content of a text layer, rasterized via the Canvas bridge. */
data class TextSpec(
    val text: String,
    /** Font size as a fraction of scene height (e.g. 0.05 = 5% of scene height). */
    val sizeFraction: Float = 0.06f,
    /** ARGB color. */
    val colorArgb: Long = 0xFFFFFFFFL,
    val bold: Boolean = true,
    /** Optional semi-transparent box behind the text, ARGB; 0 = none. */
    val backgroundArgb: Long = 0L,
    val alignment: TextAlignment = TextAlignment.CENTER,
)

/**
 * A single renderable layer. Layers are ordered bottom-to-top inside a scene.
 * `sourceId` links media layers to textures registered with the engine:
 *  - camera/video layers: engine creates the external texture and hands a
 *    [android.view.Surface] to the app through [com.vcamstudio.engine.render.render.EngineListener].
 *  - image/text layers: app provides bitmaps via RenderEngine.openBitmapSource().
 */
sealed interface LayerDefinition {
    val id: String
    val name: String
    val visible: Boolean
    val locked: Boolean
    /** 0..1 */
    val opacity: Float
    val blendMode: BlendMode
    val transform: LayerTransform
    val effects: LayerEffects

    data class Camera(
        override val id: String,
        override val name: String = "Camera",
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val transform: LayerTransform = LayerTransform(),
        override val effects: LayerEffects = LayerEffects(),
        val lensFacing: LensFacing = LensFacing.FRONT,
    ) : LayerDefinition

    data class Image(
        override val id: String,
        val sourceId: String,
        override val name: String = "Image",
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val transform: LayerTransform = LayerTransform(),
        override val effects: LayerEffects = LayerEffects(),
    ) : LayerDefinition

    data class Video(
        override val id: String,
        val sourceId: String,
        override val name: String = "Video",
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val transform: LayerTransform = LayerTransform(),
        override val effects: LayerEffects = LayerEffects(),
    ) : LayerDefinition

    data class Text(
        override val id: String,
        val spec: TextSpec,
        override val name: String = "Text",
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val transform: LayerTransform = LayerTransform(
            width = 0.8f,
            height = 0.15f,
            centerY = 0.85f,
            fitMode = FitMode.FIT,
        ),
        override val effects: LayerEffects = LayerEffects(),
    ) : LayerDefinition

    data class Color(
        override val id: String,
        /** ARGB int (e.g. 0xFF22D3EE.toInt()). */
        val color: Int,
        override val name: String = "Color",
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val transform: LayerTransform = LayerTransform(),
        override val effects: LayerEffects = LayerEffects(),
    ) : LayerDefinition
}
