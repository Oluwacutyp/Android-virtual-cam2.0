package com.vcamstudio.engine.render.shaders

import com.vcamstudio.engine.render.gl.GlProgram

/**
 * All GLSL ES 3.0 shader sources for the engine, built once at start.
 *
 * Every source begins immediately with `#version 300 es` (no leading newline —
 * a classic silent-compile-failure). The external-OES variant requires the
 * `GL_OES_EGL_image_external_essl3` extension (the ES3 flavor of the sampler).
 */
object Shaders {

    /** Shared vertex shader: quad geometry + UVs are fully baked on the CPU. */
    const val VERTEX_PASS_THROUGH = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec2 aLocal;
out vec2 vUV;
out vec2 vLocal;
void main() {
    vUV = aUV;
    vLocal = aLocal;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val ROUNDED_ALPHA = """
float roundedAlpha(vec2 local, vec2 sizePx, float radiusPx) {
    if (radiusPx <= 0.0) return 1.0;
    vec2 p = (local - 0.5) * sizePx;
    vec2 h = sizePx * 0.5;
    vec2 q = abs(p) - (h - vec2(radiusPx));
    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radiusPx;
    float aa = max(fwidth(d), 1e-4);
    return 1.0 - smoothstep(-aa, aa, d);
}
"""

    private const val APPLY_GRADE = """
vec3 applyGrade(vec3 c) {
    c += uBrightness;
    c = (c - 0.5) * uContrast + 0.5;
    c.r += uTemperature * 0.10;
    c.b -= uTemperature * 0.10;
    c.g += uTint * 0.10;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, uSaturation);
    c = pow(max(c, vec3(0.0)), vec3(1.0 / max(uGamma, 0.01)));
    return clamp(c, 0.0, 1.0);
}
"""

    private const val VIGNETTE = """
    if (uVignette.z > 0.001) {
        float dist = distance(vLocal, vec2(0.5)) * 1.41421356;
        float v = smoothstep(uVignette.x, uVignette.y, dist);
        c *= 1.0 - v * uVignette.z;
    }
"""

    private const val SHARPEN_3X3 = """
    if (uSharpen > 0.001) {
        vec3 blurSum = vec3(0.0);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                blurSum += texture(uTex, vUV + vec2(float(x), float(y)) * uTexelSize).rgb;
            }
        }
        vec3 blurred = blurSum / 9.0;
        c = c + (c - blurred) * uSharpen;
    }
"""

    private const val FX_UNIFORMS = """
uniform SAMPLER uTex;
uniform vec2 uTexelSize;
uniform float uOpacity;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uGamma;
uniform float uTemperature;
uniform float uTint;
uniform float uSharpen;
uniform vec3 uVignette;
uniform vec2 uQuadSizePx;
uniform float uCornerPx;
"""

    /**
     * The workhorse textured-layer shader.
     *
     * @param samplerType "sampler2D" for images/text/video-fallback textures,
     *   "samplerExternalOES" for camera/SurfaceTexture sources.
     * @param withSharpen 3x3 unsharp mask requires random-access sampling,
     *   which some drivers dislike on OES samplers — enabled for 2D only.
     */
    fun textureFxFragment(samplerType: String, withSharpen: Boolean, withLut: Boolean = false): String {
        val ext = if (samplerType == "samplerExternalOES") {
            "#extension GL_OES_EGL_image_external_essl3 : require\n"
        } else {
            ""
        }
        val sharpen = if (withSharpen) SHARPEN_3X3 else ""
        return buildString {
            append("#version 300 es\n")
            append(ext)
            append("precision highp float;\n")
            append("in vec2 vUV;\nin vec2 vLocal;\nout vec4 fragColor;\n")
            append(FX_UNIFORMS.replace("SAMPLER", samplerType))
            if (withLut) append("uniform sampler3D uLut3d;\nuniform float uLutMix;\n")
            append(ROUNDED_ALPHA)
            append(APPLY_GRADE)
            append("void main() {\n")
            append("    vec3 c = texture(uTex, vUV).rgb;\n")
            append(sharpen)
            append("    c = applyGrade(c);\n")
            if (withLut) append("    if (uLutMix > 0.001) c = mix(c, texture(uLut3d, c).rgb, uLutMix);\n")
            append(VIGNETTE)
            append("    float a = uOpacity * roundedAlpha(vLocal, uQuadSizePx, uCornerPx);\n")
            append("    fragColor = vec4(c * a, a);\n")
            append("}\n")
        }
    }

    const val FRAG_FILL = """#version 300 es
precision highp float;
in vec2 vUV;
in vec2 vLocal;
out vec4 fragColor;
uniform vec4 uColor;      // straight (unpremultiplied) RGBA
uniform float uOpacity;
uniform vec2 uQuadSizePx;
uniform float uCornerPx;
""" + ROUNDED_ALPHA + """
void main() {
    float a = uColor.a * uOpacity * roundedAlpha(vLocal, uQuadSizePx, uCornerPx);
    fragColor = vec4(uColor.rgb * a, a);
}
"""

    /** Separable 5-tap gaussian (linear-sample optimized). uDir = step*radius. */
    const val FRAG_BLUR = """#version 300 es
precision highp float;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
    vec4 sum = texture(uTex, vUV) * 0.22702703;
    sum += texture(uTex, vUV + uDir * 1.38461538) * 0.31621622;
    sum += texture(uTex, vUV - uDir * 1.38461538) * 0.31621622;
    sum += texture(uTex, vUV + uDir * 3.23076923) * 0.07027027;
    sum += texture(uTex, vUV - uDir * 3.23076923) * 0.07027027;
    fragColor = sum;
}
"""

    /**
     * Layer-over-scene compositing with blend modes. Inputs are premultiplied;
     * math modes unpremultiply, blend, then re-multipli.
     * uMode: see [com.vcamstudio.engine.render.model.BlendMode.glslId].
     */
    const val FRAG_BLEND = """#version 300 es
precision highp float;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform sampler2D uDst;
uniform int uMode;
uniform float uOpacity;

vec3 blendChannel(int mode, vec3 s, vec3 d) {
    if (mode == 1) return s * d;
    if (mode == 2) return s + d - s * d;
    if (mode == 3) {
        vec3 lo = 2.0 * s * d;
        vec3 hi = 1.0 - 2.0 * (1.0 - s) * (1.0 - d);
        return mix(lo, hi, step(vec3(0.5), d));
    }
    if (mode == 4) return min(s, d);
    if (mode == 5) return max(s, d);
    if (mode == 6) return min(vec3(1.0), d / max(vec3(1.0) - s, vec3(0.004)));
    if (mode == 7) return max(vec3(0.0), vec3(1.0) - (vec3(1.0) - d) / max(s, vec3(0.004)));
    if (mode == 8) {
        vec3 lo = 2.0 * d * s;
        vec3 hi = 1.0 - 2.0 * (1.0 - d) * (1.0 - s);
        return mix(lo, hi, step(vec3(0.5), s));
    }
    if (mode == 9) return (1.0 - 2.0 * d) * s * s + 2.0 * d * s;
    if (mode == 10) return abs(d - s);
    if (mode == 11) return s + d - 2.0 * s * d;
    if (mode == 12) return min(vec3(1.0), s + d);
    return s;
}

void main() {
    vec4 s = texture(uSrc, vUV) * uOpacity;
    vec4 d = texture(uDst, vUV);
    if (uMode == 0) {
        fragColor = s + d * (1.0 - s.a);
    } else {
        vec3 sc = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
        vec3 dc = d.a > 0.0 ? d.rgb / d.a : vec3(0.0);
        vec3 b = blendChannel(uMode, sc, dc);
        float outA = s.a + d.a * (1.0 - s.a);
        vec3 outRgb = b * s.a + dc * (1.0 - s.a);
        fragColor = vec4(outRgb * outA, outA);
    }
}
"""

    /** Passthrough copy (present-to-window, FBO copies, transition fading). */
    const val FRAG_COPY = """#version 300 es
precision highp float;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uAlpha;
void main() {
    fragColor = texture(uTex, vUV) * uAlpha;
}
"""

    /** Builds every program the engine needs. Throws on shader bugs (fail fast). */
    fun buildPrograms(): Programs = Programs(
        tex2d = GlProgram(VERTEX_PASS_THROUGH, textureFxFragment("sampler2D", withSharpen = true)),
        texOes = GlProgram(VERTEX_PASS_THROUGH, textureFxFragment("samplerExternalOES", withSharpen = false)),
        tex2dLut = GlProgram(VERTEX_PASS_THROUGH, textureFxFragment("sampler2D", withSharpen = true, withLut = true)),
        texOesLut = GlProgram(VERTEX_PASS_THROUGH, textureFxFragment("samplerExternalOES", withSharpen = false, withLut = true)),
        fill = GlProgram(VERTEX_PASS_THROUGH, FRAG_FILL),
        blur = GlProgram(VERTEX_PASS_THROUGH, FRAG_BLUR),
        blend = GlProgram(VERTEX_PASS_THROUGH, FRAG_BLEND),
        copy = GlProgram(VERTEX_PASS_THROUGH, FRAG_COPY),
    )

    class Programs(
        val tex2d: GlProgram,
        val texOes: GlProgram,
        val tex2dLut: GlProgram,
        val texOesLut: GlProgram,
        val fill: GlProgram,
        val blur: GlProgram,
        val blend: GlProgram,
        val copy: GlProgram,
    ) {
        fun releaseAll() {
            tex2d.release(); texOes.release(); tex2dLut.release(); texOesLut.release()
            fill.release()
            blur.release(); blend.release(); copy.release()
        }
    }
}
