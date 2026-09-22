package com.vcamstudio.engine.render.source

/**
 * A texture the compositor can sample. All methods run on the render thread.
 */
interface TextureSource {
    val sourceId: String

    /** Current content size in pixels (drives fit/aspect math). */
    val width: Int
    val height: Int

    /** GL_TEXTURE_2D or GL_TEXTURE_EXTERNAL_OES. */
    val target: Int

    /** GL texture object name for glBindTexture. */
    val glTextureId: Int

    /**
     * Pulses pending content into the GL texture.
     * @return true if new content arrived this frame (marks scene dirty).
     */
    fun update(): Boolean

    /** Whether at least one frame/content has been uploaded (skip drawing before). */
    fun hasContent(): Boolean

    fun release()
}
