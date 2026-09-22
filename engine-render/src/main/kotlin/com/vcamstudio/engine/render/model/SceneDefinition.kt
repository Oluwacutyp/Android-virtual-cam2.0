package com.vcamstudio.engine.render.model

/** Scene transition played when the active scene changes. */
enum class TransitionType { CUT, FADE }

data class TransitionSpec(
    val type: TransitionType = TransitionType.CUT,
    /** Duration in ms for FADE (CUT ignores it). */
    val durationMs: Long = 300L,
) {
    companion object {
        val CUT = TransitionSpec(TransitionType.CUT, 0L)
        val DEFAULT_FADE = TransitionSpec(TransitionType.FADE, 300L)
    }
}

/**
 * Immutable scene definition — a named stack of layers rendered bottom-to-top
 * at [width] x [height] scene pixels. Resolution-independent transforms let the
 * same scene render at 720p or 1080p.
 */
data class SceneDefinition(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** Bottom-to-top draw order. */
    val layers: List<LayerDefinition> = emptyList(),
    /** Scene background ARGB (opaque by default so the stage never reads transparent/black bug). */
    val backgroundArgb: Long = 0xFF000000L,
) {
    /** External (engine-owned) source ids referenced by this scene. */
    fun externalSourceIds(): List<String> = layers.mapNotNull { layer ->
        when (layer) {
            is LayerDefinition.Camera -> layer.id
            is LayerDefinition.Video -> layer.sourceId
            else -> null
        }
    }
}
