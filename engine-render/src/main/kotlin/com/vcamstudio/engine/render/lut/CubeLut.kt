package com.vcamstudio.engine.render.lut

/** A parsed 3D color LUT, normalized to the 0..1 input domain, GPU-ready. */
class Lut3D(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..128) { "LUT size $size outside 2..128" }
        require(data.size == size * size * size * 3) { "LUT data mismatch" }
    }
}

/**
 * Parser for the industry-standard `.cube` (Iridas/Adobe) LUT format, 3D
 * tables only. Pure Kotlin (JVM unit-tested); no Android dependencies.
 *
 * Tolerant by design: unknown metadata lines (vendor headers like CREATED /
 * SOFTWARE / DESCRIPTION, stray titles) are skipped, commas as decimal
 * separators are accepted, and [decode] handles UTF-8/UTF-16 encodings with
 * byte-order marks — real-world .cube exports do all of these.
 *
 * File table order is red-fastest, blue-slowest — exactly what
 * GL_TEXTURE_3D expects when x=red, y=green, z=blue.
 */
object CubeLutParser {

    private val WHITESPACE = Regex("\\s+")

    /** Decodes LUT file bytes to text: strips BOMs, detects UTF-16. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        if (bytes.size >= 3 && bytes[0].toInt() == 0xEF && bytes[1].toInt() == 0xBB && bytes[2].toInt() == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // BOM-less UTF-16 heuristic: ASCII text never contains NUL bytes.
        val nulCount = bytes.count { it.toInt() == 0 }
        if (nulCount > bytes.size / 4) {
            return String(bytes, Charsets.UTF_16LE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun parse(text: String): Lut3D {
        var size = -1
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(16 * 16 * 16 * 3)

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val tokens = line.split(WHITESPACE)
            when (tokens[0].lowercase()) {
                "title", "description", "creator", "comments", "license", "version", "date", "software" -> Unit
                "lut_3d_size" -> {
                    size = tokens.getOrNull(1)?.toIntOrNull()
                        ?: throw IllegalArgumentException("bad LUT_3D_SIZE: $line")
                    require(size in 2..128) { "unsupported LUT_3D_SIZE $size (2..128)" }
                }
                "lut_1d_size" -> throw IllegalArgumentException("1D LUTs are not supported")
                "domain_min" -> domainMin = parseVec(tokens)
                "domain_max" -> domainMax = parseVec(tokens)
                else -> {
                    val rgb = parseFloatRow(tokens)
                    if (rgb != null) {
                        require(size > 0) {
                            "table data before LUT_3D_SIZE — file may be corrupt or a 1D LUT"
                        }
                        values += rgb[0]; values += rgb[1]; values += rgb[2]
                    }
                    // else: unknown metadata line — tolerated and skipped.
                }
            }
        }

        require(size > 0) { "missing LUT_3D_SIZE header" }
        val expected = size * size * size * 3
        require(values.size == expected) {
            "expected $expected floats for size $size, got ${values.size} — not a valid 3D .cube table"
        }
        val data = FloatArray(expected)
        for (i in 0 until expected) {
            val c = i % 3
            val range = domainMax[c] - domainMin[c]
            data[i] = if (range > 1e-6f) (values[i] - domainMin[c]) / range else values[i]
        }
        return Lut3D(size, data)
    }

    private fun parseVec(tokens: List<String>): FloatArray =
        FloatArray(3) { tokens[it + 1].toFloatOrNull() ?: 0f }

    /** First three tokens as floats (comma decimals tolerated); null if not a data row. */
    private fun parseFloatRow(tokens: List<String>): FloatArray? {
        if (tokens.size < 3) return null
        val out = FloatArray(3)
        for (i in 0 until 3) {
            val v = tokens[i].toFloatOrNull()
                ?: tokens[i].replace(',', '.').toFloatOrNull()
                ?: return null
            out[i] = v
        }
        return out
    }
}
