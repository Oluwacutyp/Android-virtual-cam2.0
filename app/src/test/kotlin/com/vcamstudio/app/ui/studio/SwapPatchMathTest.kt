package com.vcamstudio.app.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * r64.1: placement math for the live-swap SCENE layer. Expected values are
 * HAND-COMPUTED for a 720x1280 scene with a full-frame 480x640 upright
 * camera source (the device's actual geometry) and the r63 FACE_BOX
 * [0.366, 0.628, 0.804, 1.000]:
 *
 *   scale = max(720/480, 1280/640) = 2.0
 *   offX  = (720 - 480*2)/2 = -120      offY = (1280 - 640*2)/2 = 0
 *   x1px  = -120 + 0.366*960 = 231.36   x2px = -120 + 0.804*960 = 651.84
 *   y1px  = 0.628*1280 = 803.84         y2px = 1280
 *   cx = 441.6/720 = 0.6133333          cy = 1041.92/1280 = 0.814
 *   boxW = 420.48  boxH = 476.16  size = 476.16*1.25 = 595.2
 *   wfrac = 595.2/720 = 0.8266667   hfrac = 595.2/1280 = 0.465
 *   mirrored box x: [1-0.804, 1-0.366] = [0.196, 0.634]
 *     -> x1px = 68.16  x2px = 488.64  cx = 278.4/720 = 0.3866667
 */
class SwapPatchMathTest {

    private val box = floatArrayOf(0.366f, 0.628f, 0.804f, 1.0f)
    private val d = 1e-3f

    private fun fullCam() = SwapPatchMath.patch(
        box, 480, 640,
        camCenterX = 0.5f, camCenterY = 0.5f, camWidth = 1f, camHeight = 1f,
        camMirrorX = false, sceneW = 720, sceneH = 1280,
    )

    @Test
    fun `unmirrored full-frame camera maps the box through the fill-crop`() {
        val p = fullCam()!!
        assertEquals(0.6133333f, p.centerX, d)
        assertEquals(0.814f, p.centerY, d)
        // Fractions are PER-AXIS: a 595.2 px square in 720x1280 is
        // 595.2/720 = 0.8266667 wide and 595.2/1280 = 0.465 tall.
        assertEquals(0.8266667f, p.width, d)
        assertEquals(0.465f, p.height, d)
    }

    @Test
    fun `patch is square in PIXELS regardless of box aspect`() {
        val p = fullCam()!!
        assertEquals(p.width * 720f, p.height * 1280f, 1e-2f)
    }

    @Test
    fun `mirrored camera places the patch at the mirrored x and flips the texture`() {
        val p = SwapPatchMath.patch(
            box, 480, 640,
            camCenterX = 0.5f, camCenterY = 0.5f, camWidth = 1f, camHeight = 1f,
            camMirrorX = true, sceneW = 720, sceneH = 1280,
        )!!
        assertEquals(0.3866667f, p.centerX, d)
        assertEquals(0.814f, p.centerY, d)
        assertEquals(0.8266667f, p.width, d)
        assertEquals(0.465f, p.height, d)
        assertEquals(true, p.mirrorX)
    }

    @Test
    fun `off-center partial-width camera layer shifts the patch`() {
        // camCenterX=0.25, camWidth=0.5 -> layerW=360, offX = (0 - (360-960)/2) = -300
        // x1px = -300 + 351.36 = 51.36  x2px = -300 + 771.84 = 471.84
        // cx = 261.6/720 = 0.3633333
        val p = SwapPatchMath.patch(
            box, 480, 640,
            camCenterX = 0.25f, camCenterY = 0.5f, camWidth = 0.5f, camHeight = 1f,
            camMirrorX = false, sceneW = 720, sceneH = 1280,
        )!!
        assertEquals(0.3633333f, p.centerX, d)
        assertEquals(0.814f, p.centerY, d)
    }

    @Test
    fun `grow multiplier scales the square`() {
        val p = SwapPatchMath.patch(
            box, 480, 640,
            camCenterX = 0.5f, camCenterY = 0.5f, camWidth = 1f, camHeight = 1f,
            camMirrorX = false, sceneW = 720, sceneH = 1280, grow = 1f,
        )!!
        assertEquals(476.16f / 720f, p.width, d)
    }

    @Test
    fun `degenerate inputs return null and never throw`() {
        assertNull(SwapPatchMath.patch(floatArrayOf(0.1f, 0.2f, 0.3f), 480, 640, 0.5f, 0.5f, 1f, 1f, false, 720, 1280))
        assertNull(SwapPatchMath.patch(box, 0, 640, 0.5f, 0.5f, 1f, 1f, false, 720, 1280))
        assertNull(SwapPatchMath.patch(box, 480, 0, 0.5f, 0.5f, 1f, 1f, false, 720, 1280))
        assertNull(SwapPatchMath.patch(box, 480, 640, 0.5f, 0.5f, 0f, 1f, false, 720, 1280))
        assertNull(SwapPatchMath.patch(box, 480, 640, 0.5f, 0.5f, 1f, 1f, false, 0, 1280))
    }
}
