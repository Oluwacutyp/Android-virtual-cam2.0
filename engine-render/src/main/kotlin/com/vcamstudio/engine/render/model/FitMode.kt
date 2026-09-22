package com.vcamstudio.engine.render.model

/** How a source with a different aspect ratio is fitted into the layer box. */
enum class FitMode {
    /** Scale to fit entirely inside the box (letterbox). */
    FIT,

    /** Scale to cover the box, center-cropping the overflow. */
    FILL,

    /** Ignore aspect; stretch source to exactly the box. */
    STRETCH,
}
