package com.vcamstudio.engine.render.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EglAttribsTest {

    @Test
    fun `flattens pairs and terminates with EGL_NONE`() {
        val flat = EglAttribs.flatten(
            listOf(
                0x3020 to 3,          // pretend key/value
                0x3024 to 8,
            ),
        )
        assertArrayEquals(intArrayOf(0x3020, 3, 0x3024, 8, 0x3038), flat)
    }

    @Test
    fun `empty list is just the terminator`() {
        assertArrayEquals(intArrayOf(EglAttribs.NONE), EglAttribs.flatten(emptyList()))
    }

    @Test
    fun `length is always odd (pairs plus terminator)`() {
        val flat = EglAttribs.flatten((1..7).map { it to it * 10 })
        assertEquals(15, flat.size)
        assertEquals(EglAttribs.NONE, flat.last())
    }

    @Test
    fun `real config chain interleaves correctly`() {
        val EGL_RENDERABLE_TYPE = 0x3040
        val EGL_SURFACE_TYPE = 0x3033
        val EGL_RED_SIZE = 0x3024
        val flat = EglAttribs.flatten(
            listOf(
                EGL_RENDERABLE_TYPE to 0x0040,
                EGL_SURFACE_TYPE to (0x0001 or 0x0002),
                EGL_RED_SIZE to 8,
            ),
        )
        assertEquals(EGL_RENDERABLE_TYPE, flat[0])
        assertEquals(0x0040, flat[1])
        assertEquals(EGL_SURFACE_TYPE, flat[2])
        assertEquals(3, flat[3])
        assertEquals(EGL_RED_SIZE, flat[4])
        assertEquals(8, flat[5])
        assertTrue(flat.last() == EglAttribs.NONE)
    }
}
