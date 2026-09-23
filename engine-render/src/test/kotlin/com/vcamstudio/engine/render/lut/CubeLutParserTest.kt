package com.vcamstudio.engine.render.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {

    private val neutral2 = """
        # neutral identity 2x2x2 LUT
        TITLE "test"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun `parses 2x2x2 with red-fastest order`() {
        val lut = CubeLutParser.parse(neutral2)
        assertEquals(2, lut.size)
        assertEquals(24, lut.data.size)
        // Entry 0 (r0,g0,b0) = black; entry 1 (r1,g0,b0) = pure red.
        assertEquals(0f, lut.data[0], 1e-6f)
        assertEquals(1f, lut.data[3], 1e-6f)
        assertEquals(0f, lut.data[4], 1e-6f)
        // Entry 2 (r0,g1,b0) = pure green at floats 6..8.
        assertEquals(1f, lut.data[7], 1e-6f)
        // Entry 4 (r0,g0,b1) = pure blue at floats 12..14.
        assertEquals(1f, lut.data[14], 1e-6f)
    }

    @Test
    fun `domain remap normalizes to full range`() {
        val cube = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.25 0.0 0.0
            DOMAIN_MAX 0.75 1.0 1.0
            0.25 0.0 0.0
            0.75 0.0 0.0
            0.25 1.0 0.0
            0.75 1.0 0.0
            0.25 0.0 1.0
            0.75 0.0 1.0
            0.25 1.0 1.0
            0.75 1.0 1.0
        """.trimIndent()
        val lut = CubeLutParser.parse(cube)
        assertEquals(0f, lut.data[0], 1e-6f)
        assertEquals(1f, lut.data[3], 1e-6f)
        assertEquals(0f, lut.data[4], 1e-6f)
    }

    @Test
    fun `trailing comments and blank lines are ignored`() {
        val cube = "LUT_3D_SIZE 2\n\n# comment\n0.0 0.0 0.0 # inline\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1"
        assertEquals(2, CubeLutParser.parse(cube).size)
    }

    @Test
    fun `count mismatch rejects`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            CubeLutParser.parse("LUT_3D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0")
        }
        assertTrue(e.message!!.contains("expected"))
    }

    @Test
    fun `missing size header rejects`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLutParser.parse("0.0 0.0 0.0")
        }
    }

    @Test
    fun `one-dimensional lut rejects`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLutParser.parse("LUT_1D_SIZE 32\n0 0 0")
        }
    }

    @Test
    fun `unknown vendor metadata lines are skipped`() {
        val cube = """
            CREATED "2024-01-01"
            SOFTWARE "SomeGrader 9.1"
            DESCRIPTION "a look"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        assertEquals(2, CubeLutParser.parse(cube).size)
    }

    @Test
    fun `comma decimals tolerated`() {
        val rows = ArrayList<String>()
        for (r in 0 until 2) {
            for (g in 0 until 2) {
                for (b in 0 until 2) {
                    rows.add("$r,0 $g,0 $b,0")
                }
            }
        }
        val cube = "LUT_3D_SIZE 2\n" + rows.joinToString("\n")
        assertEquals(2, CubeLutParser.parse(cube).size)
    }

    @Test
    fun `utf16 bom files decode`() {
        val text = "LUT_3D_SIZE 2\n" + neutral2.lineSequence().drop(2).joinToString("\n")
        val bytes = ByteArray(2) { i -> if (i == 0) 0xFF.toByte() else 0xFE.toByte() } +
            text.toByteArray(Charsets.UTF_16LE)
        val decoded = CubeLutParser.decode(bytes)
        assertTrue(decoded.contains("LUT_3D_SIZE"))
        assertEquals(2, CubeLutParser.parse(decoded).size)
    }

    @Test
    fun `utf8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            neutral2.toByteArray(Charsets.UTF_8)
        assertEquals(2, CubeLutParser.parse(CubeLutParser.decode(bytes)).size)
    }
}
