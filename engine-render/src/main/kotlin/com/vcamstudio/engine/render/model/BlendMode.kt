package com.vcamstudio.engine.render.model

/** Porter-Duff NORMAL + Photoshop-style pixel blend modes for layer compositing. */
enum class BlendMode(val glslId: Int) {
    NORMAL(0),
    MULTIPLY(1),
    SCREEN(2),
    OVERLAY(3),
    DARKEN(4),
    LIGHTEN(5),
    COLOR_DODGE(6),
    COLOR_BURN(7),
    HARD_LIGHT(8),
    SOFT_LIGHT(9),
    DIFFERENCE(10),
    EXCLUSION(11),
    LINEAR_DODGE_ADD(12);

    companion object {
        fun fromId(id: Int): BlendMode = entries.firstOrNull { it.glslId == id } ?: NORMAL
    }
}
