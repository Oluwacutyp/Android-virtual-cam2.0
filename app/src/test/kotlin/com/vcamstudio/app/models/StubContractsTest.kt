package com.vcamstudio.app.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r52a: the stub models' contracts, asserted against the COMMITTED bytes
 * (no ORT on the desktop JVM — the behavioral checks run on device as
 * MODEL_PROBE_* lines in the child log). A minimal protobuf walker reads
 * exactly the ONNX proto fields these assertions need.
 *
 * Verified at generation time with desktop onnxruntime as well
 * (scripts/make_stubs.py runs onnx.checker + execute checks).
 */
class StubContractsTest {

    // ---- minimal protobuf ------------------------------------------------

    private class Field(val no: Int, val wire: Int, val vlong: Long, val vbytes: ByteArray)

    private class Reader(private val b: ByteArray) {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var sh = 0
            while (true) {
                check(i < b.size) { "varint overrun" }
                val x = b[i++].toInt()
                r = r or ((x and 0x7F).toLong() shl sh)
                if (x and 0x80 == 0) return r
                sh += 7
            }
        }
    }

    private fun parse(b: ByteArray): List<Field> {
        val r = Reader(b)
        val out = ArrayList<Field>()
        while (r.i < b.size) {
            val tag = r.varint()
            val no = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            when (wire) {
                0 -> out.add(Field(no, 0, r.varint(), ByteArray(0)))
                2 -> {
                    val len = r.varint().toInt()
                    val payload = b.copyOfRange(r.i, r.i + len)
                    r.i += len
                    out.add(Field(no, 2, 0, payload))
                }
                5 -> { r.i += 4; out.add(Field(no, 5, 0, ByteArray(0))) }
                1 -> { r.i += 8; out.add(Field(no, 1, 0, ByteArray(0))) }
                else -> error("wire $wire")
            }
        }
        return out
    }

    private fun msg(f: Field) = parse(f.vbytes)
    private fun str(f: Field) = String(f.vbytes, Charsets.UTF_8)
    private fun first(fs: List<Field>, no: Int) = fs.firstOrNull { it.no == no }

    // ---- ONNX proto accessors (field numbers from onnx.proto) ------------

    private class TypeInfo(val elemType: Int, val dims: List<Pair<Long, String?>>)

    private fun valueInfo(fs: List<Field>): Pair<String, TypeInfo> {
        val name = str(first(fs, 1)!!)
        val typeProto = msg(first(fs, 2)!!)
        val tensor = msg(first(typeProto, 1)!!)
        val elem = first(tensor, 1)?.vlong?.toInt() ?: 1
        val shapeMsg = first(tensor, 2)?.let { msg(it) }
        val dims = ArrayList<Pair<Long, String?>>()
        if (shapeMsg != null) {
            for (d in shapeMsg.filter { it.no == 1 }) {
                val dm = msg(d)
                dims.add(Pair(first(dm, 1)?.vlong ?: -1L, first(dm, 2)?.let { str(it) }))
            }
        }
        return Pair(name, TypeInfo(elem, dims))
    }

    private class Node(
        val opType: String,
        val inputs: List<String>,
        val outputs: List<String>,
    )

    private fun graph(bytes: ByteArray): Triple<List<Node>, List<Pair<String, TypeInfo>>, List<Pair<String, TypeInfo>>> {
        val model = parse(bytes)
        val g = msg(first(model, 7)!!)
        val nodes = ArrayList<Node>()
        for (n in g.filter { it.no == 1 }) {
            val nf = msg(n)
            nodes.add(
                Node(
                    opType = first(nf, 4)?.let { str(it) } ?: "?",
                    inputs = nf.filter { it.no == 1 }.map { str(it) },
                    outputs = nf.filter { it.no == 2 }.map { str(it) },
                ),
            )
        }
        val inputs = g.filter { it.no == 11 }.map { valueInfo(msg(it)) }
        val outputs = g.filter { it.no == 12 }.map { valueInfo(msg(it)) }
        return Triple(nodes, inputs, outputs)
    }

    /** Initializers: (name, (dataType, dims)) — graph field 5. */
    private fun initializers(bytes: ByteArray): List<Pair<String, Pair<Int, List<Long>>>> {
        val model = parse(bytes)
        val g = msg(first(model, 7)!!)
        return g.filter { it.no == 5 }.map { t ->
            val tf = msg(t)
            val name = str(first(tf, 8)!!)
            val dtype = first(tf, 2)?.vlong?.toInt() ?: 1
            val dims = tf.filter { it.no == 1 }.map { it.vlong }
            Pair(name, Pair(dtype, dims))
        }
    }

    private fun stub(name: String): File {
        val rel = File("src/debug/assets/stubs/$name")
        return if (rel.exists()) rel else File("app/src/debug/assets/stubs/$name")
    }

    // ---- the contracts ---------------------------------------------------

    @Test
    fun `w600k stub contract - input1 to 683 with 512 logits`() {
        val (nodes, inputs, outputs) = graph(stub("stub_w600k_r50.onnx").readBytes())
        assertEquals(listOf("input.1"), inputs.map { it.first })
        assertEquals(listOf("683"), outputs.map { it.first })
        val i = inputs[0].second
        assertEquals(1, i.elemType) // FLOAT
        assertEquals(listOf(-1L to "None", 3L to null, 112L to null, 112L to null), i.dims)
        assertEquals(listOf(1L, 512L), outputs[0].second.dims.map { it.first })
        assertTrue(nodes.any { it.opType == "MatMul" })
    }

    @Test
    fun `inswapper stub contract - target and source, and output depends on BOTH`() {
        val (nodes, inputs, outputs) = graph(stub("stub_inswapper_128.onnx").readBytes())
        assertEquals(
            listOf("target" to listOf(1L, 3L, 128L, 128L), "source" to listOf(1L, 512L)),
            inputs.map { it.first to it.second.dims.map { d -> d.first } },
        )
        assertEquals("output", outputs[0].first)
        assertEquals(listOf(1L, 3L, 128L, 128L), outputs[0].second.dims.map { it.first })
        // Static dependency: walk backwards from the output; both inputs
        // must be reachable (the stub exists to catch an ignored 'source').
        val reachable = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add("output")
        while (queue.isNotEmpty()) {
            val want = queue.removeFirst()
            for (n in nodes) {
                if (want in n.outputs) {
                    for (inp in n.inputs) {
                        if (reachable.add(inp)) queue.add(inp)
                    }
                }
            }
        }
        assertTrue("source not reachable from output", "source" in reachable)
        assertTrue("target not reachable from output", "target" in reachable)
    }

    @Test
    fun `fp16 conv probe - ops contain Conv, fp16 weights, exact shapes`() {
        // r54-D: an fp16 probe WITHOUT a Conv cannot fail like the real
        // model; the committed probe must be Cast,Conv,Relu,Cast with fp16
        // [4,3,3,3] weights and fp32 [1,3,16,16] -> [1,4,14,14] I/O.
        val (nodes, inputs, outputs) = graph(stub("probe_fp16_conv.onnx").readBytes())
        assertEquals(listOf("Cast", "Conv", "Relu", "Cast"), nodes.map { it.opType })
        assertEquals(1, inputs[0].second.elemType) // FLOAT in
        assertEquals(listOf(1L, 3L, 16L, 16L), inputs[0].second.dims.map { it.first })
        assertEquals(1, outputs[0].second.elemType) // FLOAT out
        assertEquals(listOf(1L, 4L, 14L, 14L), outputs[0].second.dims.map { it.first })
        val conv = nodes.first { it.opType == "Conv" }
        assertTrue("W16" in conv.inputs && "B16" in conv.inputs)
        val inits = initializers(stub("probe_fp16_conv.onnx").readBytes())
        val w = inits.first { it.first == "W16" }
        assertEquals(10, w.second.first) // FLOAT16 weights
        assertEquals(listOf(4L, 3L, 3L, 3L), w.second.second)
        assertEquals(10, inits.first { it.first == "B16" }.second.first)
    }
}
