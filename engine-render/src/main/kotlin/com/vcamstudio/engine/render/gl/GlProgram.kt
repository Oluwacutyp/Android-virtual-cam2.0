package com.vcamstudio.engine.render.gl

import android.opengl.GLES30
import android.opengl.GLES32

/**
 * Compiled GLSL ES 3.0 program with a uniform-location cache.
 * Compilation/link errors throw [GlException] carrying the driver's info log —
 * shader bugs are caught at engine start, never rendered as garbage.
 */
class GlProgram(vertexSource: String, fragmentSource: String) {

    val handle: Int = createProgram(vertexSource, fragmentSource)
    private val uniformCache = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(handle)

    fun uniform(name: String): Int =
        uniformCache.getOrPut(name) { GLES30.glGetUniformLocation(handle, name) }

    fun setFloat(name: String, v: Float) = GLES30.glUniform1f(uniform(name), v)
    fun setInt(name: String, v: Int) = GLES30.glUniform1i(uniform(name), v)
    fun setVec2(name: String, a: Float, b: Float) = GLES30.glUniform2f(uniform(name), a, b)
    fun setVec3(name: String, a: Float, b: Float, c: Float) =
        GLES30.glUniform3f(uniform(name), a, b, c)
    fun setVec4(name: String, a: Float, b: Float, c: Float, d: Float) =
        GLES30.glUniform4f(uniform(name), a, b, c, d)

    fun release() {
        GLES30.glDeleteProgram(handle)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw GlException("Program link failed: $log")
        }
        // Shaders can be flagged for deletion once linked.
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlException(
                "Shader compile failed (${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}): $log",
            )
        }
        return shader
    }
}
