package com.vcamstudio.app.ui.studio

import com.vcamstudio.engine.aiface.FaceBox

/**
 * r51-C1: pure box math for the debug overlay. Operates in UPRIGHT-frame
 * normalized space — the front mirror is applied AFTER smoothing, exactly
 * once (the child's FACE_BOX and the dump stay raw/upright).
 */
internal object FaceOverlayMath {

    /** Fresh weight of the EMA (0.45 fresh + 0.55 prev per coordinate). */
    const val FRESH_WEIGHT = 0.45f

    /**
     * Per-coordinate EMA. A null previous box (first appearance / slot after
     * a miss) passes [fresh] through unchanged — never interpolate across a
     * detection gap. Score/timestamp/frame dims always come from [fresh].
     */
    fun smooth(prev: FaceBox?, fresh: FaceBox): FaceBox =
        prev?.let {
            fresh.copy(
                x1 = FRESH_WEIGHT * fresh.x1 + (1f - FRESH_WEIGHT) * it.x1,
                y1 = FRESH_WEIGHT * fresh.y1 + (1f - FRESH_WEIGHT) * it.y1,
                x2 = FRESH_WEIGHT * fresh.x2 + (1f - FRESH_WEIGHT) * it.x2,
                y2 = FRESH_WEIGHT * fresh.y2 + (1f - FRESH_WEIGHT) * it.y2,
            )
        } ?: fresh

    /** Front-camera mirror: flip x exactly once (x1' = 1 - x2, x2' = 1 - x1). */
    fun mirrorX(b: FaceBox): FaceBox = b.copy(x1 = 1f - b.x2, x2 = 1f - b.x1)
}
