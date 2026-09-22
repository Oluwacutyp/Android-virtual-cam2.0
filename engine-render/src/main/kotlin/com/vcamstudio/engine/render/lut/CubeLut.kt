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
 * File table order is red-fastest, blue-slowest — exactly what
 * GL_TEXTURE_3D expects when x=red, y=green, z=blue, so values are uploaded
 * verbatim after domain normalization.
 */
object CubeLutParser {

    private val WHITESPACE = Regex("\\s+")

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
                "title" -> Unit
                "lut_3d_size" -> {
                    size = tokens[1].toIntOrNull()
                        ?: throw IllegalArgumentException("bad LUT_3D_SIZE: $line")
                    require(size in 2..128) { "unsupported LUT_3D_SIZE $size (2..128)" }
                }
                "lut_1d_size" -> throw IllegalArgumentException("1D LUTs are not supported")
                "domain_min" -> domainMin = parseVec(tokens)
                "domain_max" -> domainMax = parseVec(tokens)
                else -> {
                    require(size > 0) { "table data before LUT_3D_SIZE" }
                    require(tokens.size >= 3) { "malformed table row: $line" }
                    repeat(3) { c ->
                        values += tokens[c].toFloatOrNull()
                            ?: throw IllegalArgumentException("bad float in: $line")
                    }
                }
            }
        }

        require(size > 0) { "missing LUT_3D_SIZE header" }
        val expected = size * size * size * 3
        require(values.size == expected) {
            "expected $expected floats for size $size, got ${values.size}"
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
}
