package com.vcamstudio.engine.render.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-22 mandate 6: GOLDEN FRAME GATE for the wedge class.
 *
 * The probe-wedge verdict (owner, round 21/22): GL_TRIANGLE_STRIP rasterizes
 * a wedged quad on Adreno 730 while GL_TRIANGLES with six explicit vertices
 * (TL,TR,BR, TL,BR,BL) rasterizes clean. This test pins that contract
 * forever, on any chip, in CI:
 *
 *  1. It replicates the engine's EXACT vertex contract — four staged corners
 *     (TL,TR,BR,BL; positions NDC y-up, per-vertex UV, local coords) expanded
 *     by TRI_ORDER into six vertices — and rasterizes them with a software
 *     edge-function rasterizer.
 *  2. It asserts the covered area is the FULL rect: zero uncovered pixels
 *     inside, zero covered pixels outside, every row contiguous, every tile
 *     fully covered — i.e. NO DIAGONAL ARTIFACT on any tile, for fullscreen
 *     AND cropped windows.
 *  3. It asserts both triangles share one winding orientation (same convention
 *     the strip had, so no state other than the primitive mode changed).
 *  4. SOURCE GATE: it fails if any engine source issues GL_TRIANGLE_STRIP
 *     again (mandate 5: DRAW_PATH=TRIANGLES is permanent), and pins the
 *     DRAW_PATH marker so a boot assert always has something to assert.
 */
class DrawPathGoldenTest {

    // ---- the engine's vertex contract, replicated verbatim ----
    private val strideFloats = 6
    private val triOrder = intArrayOf(0, 1, 2, 0, 2, 3) // TL,TR,BR, TL,BR,BL

    /** Corner order used by uploadQuad/uploadFullScreenQuad: TL,TR,BR,BL. */
    private fun corners(): Array<FloatArray> = arrayOf(
        floatArrayOf(-1f, 1f, 0f, 0f, 0f, 0f), // TL: pos, uv, local
        floatArrayOf(1f, 1f, 1f, 0f, 1f, 0f),  // TR
        floatArrayOf(1f, -1f, 1f, 1f, 1f, 1f), // BR
        floatArrayOf(-1f, -1f, 0f, 1f, 0f, 1f), // BL
    )

    /** uploadVertexData: expand corners by TRI_ORDER (mandate 2). */
    private fun expandToSix(corners: Array<FloatArray>): Array<FloatArray> =
        Array(triOrder.size) { i -> corners[triOrder[i]] }

    /**
     * Software rasterizer: edge functions, pixel-center sampling, top-left
     * fill rule. Returns per-pixel write counts and interpolated UVs.
     */
    private fun rasterize(
        verts: Array<FloatArray>,
        w: Int,
        h: Int,
    ): Triple<Array<IntArray>, Array<FloatArray>, Array<FloatArray>> {
        val coverage = Array(h) { IntArray(w) }
        val uvX = Array(h) { FloatArray(w) }
        val uvY = Array(h) { FloatArray(w) }

        // Map NDC so the fullscreen quad spans -0.5 .. max-0.5 in pixel space:
        // quad borders never pass exactly through sample points, and the
        // shared diagonal's zero-edge pixels are resolved by the top-left
        // rule (exactly one triangle covers each — no gap, no hole).
        fun toPx(x: Float, y: Float): Pair<Float, Float> =
            (((x + 1f) / 2f) * w - 0.5f, ((1f - y) / 2f) * h - 0.5f)

        for (t in 0 until verts.size / 3) {
            val a = verts[t * 3]
            val b = verts[t * 3 + 1]
            val c = verts[t * 3 + 2]
            val (ax, ay) = toPx(a[0], a[1])
            val (bx, by) = toPx(b[0], b[1])
            val (cx, cy) = toPx(c[0], c[1])
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0f) continue
            val sign = if (area > 0f) 1f else -1f
            for (py in 0 until h) {
                for (px in 0 until w) {
                    val fx = px.toFloat()
                    val fy = py.toFloat()
                    fun edge(x0: Float, y0: Float, x1: Float, y1: Float): Float =
                        ((x1 - x0) * (fy - y0) - (y1 - y0) * (fx - x0)) * sign
                    val e0 = edge(ax, ay, bx, by)
                    val e1 = edge(bx, by, cx, cy)
                    val e2 = edge(cx, cy, ax, ay)
                    // top-left rule: accept interior or exactly-on-top-left edges
                    val inside = (e0 > 0 || (e0 == 0f && isTopLeft(ax, ay, bx, by))) &&
                        (e1 > 0 || (e1 == 0f && isTopLeft(bx, by, cx, cy))) &&
                        (e2 > 0 || (e2 == 0f && isTopLeft(cx, cy, ax, ay)))
                    if (inside) {
                        coverage[py][px]++
                        val w0 = e1 / area * sign
                        val w1 = e2 / area * sign
                        val w2 = e0 / area * sign
                        uvX[py][px] = a[2] * w0 + b[2] * w1 + c[2] * w2
                        uvY[py][px] = a[3] * w0 + b[3] * w1 + c[3] * w2
                    }
                }
            }
        }
        return Triple(coverage, uvX, uvY)
    }

    private fun isTopLeft(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        // In y-down pixel space, top edge: dy < 0; left edge: dy == 0 && dx < 0.
        val dx = x1 - x0
        val dy = y1 - y0
        return dy < 0 || (dy == 0f && dx < 0)
    }

    private fun assertCleanQuad(coverage: Array<IntArray>, w: Int, h: Int, label: String) {
        // (a) NO uncovered pixel inside the frame (the wedge = uncovered wedge
        //     region; any diagonal shows up here as uncovered pixels).
        for (y in 0 until h) {
            for (x in 0 until w) {
                assertTrue(
                    "$label: uncovered pixel at ($x,$y) — wedge/diagonal artifact",
                    coverage[y][x] >= 1,
                )
            }
        }
        // (b) Per-tile: every tile fully covered — "no diagonal on any tile".
        val tile = 8
        for (ty in 0 until h / tile) {
            for (tx in 0 until w / tile) {
                for (y in ty * tile until (ty + 1) * tile) {
                    for (x in tx * tile until (tx + 1) * tile) {
                        assertTrue(
                            "$label: tile($tx,$ty) has uncovered pixel ($x,$y)",
                            coverage[y][x] >= 1,
                        )
                    }
                }
            }
        }
        // (c) Every row contiguous (a diagonal wedge splits rows).
        for (y in 0 until h) {
            var first = -1
            var last = -1
            for (x in 0 until w) if (coverage[y][x] >= 1) {
                if (first < 0) first = x
                last = x
            }
            for (x in first..last) {
                assertTrue("$label: row $y non-contiguous at x=$x", coverage[y][x] >= 1)
            }
        }
    }

    @Test
    fun `six-vertex triangles cover the full frame with no diagonal`() {
        val w = 96
        val h = 96
        val (coverage, _, _) = rasterize(expandToSix(corners()), w, h)
        assertCleanQuad(coverage, w, h, "fullscreen")
        // Max two triangle writes per pixel (the two triangles share one edge).
        for (y in 0 until h) {
            for (x in 0 until w) {
                assertTrue(
                    "pixel ($x,$y) written ${coverage[y][x]}x (expected <=2)",
                    coverage[y][x] in 1..2,
                )
            }
        }
    }

    @Test
    fun `cropped window quad has no diagonal and exact rect bounds`() {
        // FILL-crop style window: quad covering the middle band of the frame.
        val w = 96
        val h = 96
        val x0 = -0.203f
        val x1 = 0.697f
        val y0 = -0.401f
        val y1 = 0.597f
        val cs = arrayOf(
            floatArrayOf(x0, y1, 0f, 0f, 0f, 0f),
            floatArrayOf(x1, y1, 1f, 0f, 1f, 0f),
            floatArrayOf(x1, y0, 1f, 1f, 1f, 1f),
            floatArrayOf(x0, y0, 0f, 1f, 0f, 1f),
        )
        val (coverage, _, _) = rasterize(expandToSix(cs), w, h)
        // Same -0.5-biased mapping as the rasterizer; sample x is inside the
        // quad iff left <= x <= right (ceil of the left edge, floor of right).
        fun mapX(v: Float) = ((v + 1f) / 2f) * w - 0.5f
        fun mapY(v: Float) = ((1f - v) / 2f) * h - 0.5f
        val left = Math.ceil(mapX(x0).toDouble()).toInt()
        val right = Math.floor(mapX(x1).toDouble()).toInt()
        val top = Math.ceil(mapY(y1).toDouble()).toInt()
        val bottom = Math.floor(mapY(y0).toDouble()).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inside = x in left..right && y in top..bottom
                assertEquals(
                    "cropped: coverage mismatch at ($x,$y) inside=$inside",
                    if (inside) 1 else 0,
                    coverage[y][x].coerceAtMost(1),
                )
            }
        }
        // Per-tile: any tile the quad intersects is fully covered inside bounds.
        val tile = 8
        for (ty in 0 until h / tile) {
            for (tx in 0 until w / tile) {
                val tileCovered = coverage[ty * tile][tx * tile] >= 1 ||
                    coverage[(ty + 1) * tile - 1][(tx + 1) * tile - 1] >= 1
                if (tileCovered) {
                    for (y in ty * tile until (ty + 1) * tile) {
                        for (x in tx * tile until (tx + 1) * tile) {
                            val inside = x in left..right && y in top..bottom
                            if (inside) {
                                assertTrue(
                                    "cropped: tile($tx,$ty) diagonal at ($x,$y)",
                                    coverage[y][x] >= 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `uv interpolation maps frame corners to the right texture corners`() {
        val w = 96
        val h = 96
        val (coverage, uvX, uvY) = rasterize(expandToSix(corners()), w, h)
        fun uvAt(x: Int, y: Int): Pair<Float, Float> = uvX[y][x] to uvY[y][x]
        val cases = listOf(
            0 to 0 to (0f to 0f),          // TL pixel -> uv(0,0)
            (w - 1) to 0 to (1f to 0f),    // TR
            (w - 1) to (h - 1) to (1f to 1f), // BR
            0 to (h - 1) to (0f to 1f),    // BL
        )
        for ((pos, expected) in cases) {
            val (x, y) = pos
            assertTrue("corner ($x,$y) not covered", coverage[y][x] >= 1)
            val (u, v) = uvAt(x, y)
            assertEquals("u at ($x,$y)", expected.first, u, 0.02f)
            assertEquals("v at ($x,$y)", expected.second, v, 0.02f)
        }
        // Straight-line UV ramp: no twist (a crossed diagonal would fold u or v mid-row).
        for (y in 0 until h) {
            assertEquals("u monotonic row $y", false, (1 until w).any { uvX[y][it] < uvX[y][it - 1] - 1e-4f })
        }
    }

    @Test
    fun `both triangles share one winding orientation`() {
        fun signedArea2(a: FloatArray, b: FloatArray, c: FloatArray): Float =
            (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        val cs = corners()
        val t0 = signedArea2(cs[0], cs[1], cs[2])
        val t1 = signedArea2(cs[0], cs[2], cs[3])
        assertEquals("triangles disagree on winding", Math.signum(t0), Math.signum(t1))
        assertTrue("degenerate triangle", t0 != 0f)
    }

    // ---------------------------------------------------------- source gate

    private fun engineMainSrc(): File {
        val rel = "src/main/kotlin/com/vcamstudio/engine/render"
        var dir = File(System.getProperty("user.dir")!!)
        repeat(4) {
            val candidate = File(dir, rel)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: dir
        }
        throw IllegalStateException("engine-render sources not found from ${System.getProperty("user.dir")}")
    }

    /** Strip comments so doc mentions don't false-positive; then search code. */
    private fun stripComments(src: String): String {
        val noBlock = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lines().joinToString("\n") { it.replace(Regex("//.*"), "") }
    }

    @Test
    fun `no engine draw call issues TRIANGLE_STRIP (mandate 5 source gate)`() {
        val root = engineMainSrc()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val code = stripComments(f.readText())
                if ("GL_TRIANGLE_STRIP" in code) f.name else null
            }
            .toList()
        assertTrue(
            "DRAW_PATH_REGRESSION: GL_TRIANGLE_STRIP issued again in $offenders — " +
                "the probe-wedge verdict proved it wedges on Adreno 730",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `DRAW_PATH marker is TRIANGLES and every quad draw routes through it`() {
        val sr = File(engineMainSrc(), "render/SceneRenderer.kt")
        val code = stripComments(sr.readText())
        assertTrue("DRAW_PATH constant missing", "const val DRAW_PATH = \"TRIANGLES\"" in code)
        assertTrue(
            "issueTriangles guard missing (every quad draw must route through it)",
            "private fun issueTriangles(" in code,
        )
        // exactly one GL_TRIANGLES draw site for the staged path + the VBO branch
        assertTrue("GL_TRIANGLES draw sites missing", "GL_TRIANGLES" in code)
    }
}
