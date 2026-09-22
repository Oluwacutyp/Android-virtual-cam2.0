package com.vcamstudio.engine.capture

/** Which physical camera to bind. */
enum class LensFacing { FRONT, BACK }

/** White-balance presets mapped to Camera2 AWB modes. */
enum class WbMode(val awbCode: Int) {
    AUTO(1),
    OFF(0),
    INCANDESCENT(2),
    FLUORESCENT(3),
    DAYLIGHT(5),
    CLOUDY(6),
    TWILIGHT(7),
    SHADE(8),
}

/** Autofocus program for the bind-time AF mode. */
enum class FocusMode(val afCode: Int) {
    CONTINUOUS(4), // CONTROL_AF_MODE_CONTINUOUS_PICTURE
    MACRO(3),      // CONTROL_AF_MODE_MACRO
    EDOF(5),       // CONTROL_AF_MODE_EDOF
}

/**
 * Camera configuration. Fields marked "bind-time" are applied via Camera2Interop
 * when the camera (re)binds; "runtime" ones apply instantly via CameraControl.
 */
data class ProControls(
    val lensFacing: LensFacing = LensFacing.FRONT,
    /** Requested capture resolution fed into the engine's external texture. */
    val targetWidth: Int = 1280,
    val targetHeight: Int = 720,
    // bind-time
    val iso: Int? = null,
    val whiteBalance: WbMode = WbMode.AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    /** Fixed FPS range, e.g. 30..30 (null = camera default). */
    val fpsRange: IntRange? = null,
    // runtime
    val exposureCompensationIndex: Int? = null,
    val zoomRatio: Float = 1f,
    val torch: Boolean = false,
)
