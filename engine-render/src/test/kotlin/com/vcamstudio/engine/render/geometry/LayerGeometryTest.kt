package com.vcamstudio.engine.render.geometry

import com.vcamstudio.engine.render.model.FitMode
import com.vcamstudio.engine.render.model.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerGeometryTest {

    private val sceneW = 1000f
    private val sceneH = 1000f

    @Test
    fun `full screen stretch covers the scene`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.STRETCH),
            sourceWidthPx = 640f, sourceHeightPx = 480f,
            sceneWidthPx = sceneW, sceneHeightPx = sceneH,
        )
        // TL (-1..0), TR (0..1), BR, BL in scene px
        assertEquals(0f, quad.cornersPx[0], 0.01f)
        assertEquals(0f, quad.cornersPx[1], 0.01f)
        assertEquals(1000f, quad.cornersPx[4], 0.01f) // BR x
        assertEquals(1000f, quad.cornersPx[5], 0.01f) // BR y
    }

    @Test
    fun `fit letterboxes a wide source into a square box`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FIT),
            sourceWidthPx = 2000f, sourceHeightPx = 1000f,
            sceneWidthPx = sceneW, sceneHeightPx = sceneH,
        )
        // displayed size 1000x500 centered: TL=(0,250), TR=(1000,250)
        assertEquals(0f, quad.cornersPx[0], 0.01f)
        assertEquals(250f, quad.cornersPx[1], 0.01f)
        assertEquals(1000f, quad.cornersPx[2], 0.01f)
        assertEquals(250f, quad.cornersPx[3], 0.01f)
        // full uv window
        assertEquals(0f, quad.uvs[0], 1e-5f)
        assertEquals(1f, quad.uvs[4], 1e-5f)
    }

    @Test
    fun `fill center-crops the uv window`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.FILL),
            sourceWidthPx = 2000f, sourceHeightPx = 1000f,
            sceneWidthPx = sceneW, sceneHeightPx = sceneH,
        )
        // box is 1000x1000; scaled source is 2000x1000 -> crop x to 50%
        val u0 = quad.uvs[0]
        val u1 = quad.uvs[2]
        assertEquals(0.25f, u0, 1e-4f)
        assertEquals(0.75f, u1, 1e-4f)
        // v window stays full
        assertEquals(0f, quad.uvs[1], 1e-4f)
        assertEquals(1f, quad.uvs[5], 1e-4f)
        // quad covers the full box
        assertEquals(0f, quad.cornersPx[0], 0.01f)
        assertEquals(1000f, quad.cornersPx[4], 0.01f)
    }

    @Test
    fun `mirror x flips u coordinates`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.STRETCH, mirrorX = true),
            sourceWidthPx = 100f, sourceHeightPx = 100f,
            sceneWidthPx = sceneW, sceneHeightPx = sceneH,
        )
        assertEquals(1f, quad.uvs[0], 1e-5f) // TL u = 1
        assertEquals(0f, quad.uvs[2], 1e-5f) // TR u = 0
    }

    @Test
    fun `rotation moves corners`() {
        val quad = LayerGeometry.compute(
            LayerTransform(width = 1f, height = 1f, fitMode = FitMode.STRETCH, rotationDeg = 90f),
            sourceWidthPx = 100f, sourceHeightPx = 100f,
            sceneWidthPx = sceneW, sceneHeightPx = sceneH,
        )
        // 90° clockwise: TL(-500,-500 local) -> (-500*0 - (-500)*1, -500*1 + (-500)*0) = (500, -500)
        // center (500,500) -> TL = (1000, 0)
        assertEquals(1000f, quad.cornersPx[0], 0.5f)
        assertEquals(0f, quad.cornersPx[1], 0.5f)
    }

    @Test
    fun `clip space conversion flips y`() {
        val clip = LayerGeometry.toClipSpace(0f, 0f, 1000f, 1000f)
        assertEquals(-1f, clip[0], 1e-5f)
        assertEquals(1f, clip[1], 1e-5f)
        val clip2 = LayerGeometry.toClipSpace(1000f, 1000f, 1000f, 1000f)
        assertEquals(1f, clip2[0], 1e-5f)
        assertEquals(-1f, clip2[1], 1e-5f)
    }

    @Test
    fun `corner radius pixels`() {
        assertEquals(50f, LayerGeometry.cornerRadiusPx(0.5f, 100f, 200f), 1e-4f)
        assertEquals(0f, LayerGeometry.cornerRadiusPx(0f, 100f, 200f), 1e-4f)
    }
}
