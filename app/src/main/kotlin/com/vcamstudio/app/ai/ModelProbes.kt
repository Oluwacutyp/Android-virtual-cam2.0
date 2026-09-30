package com.vcamstudio.app.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

/**
 * r52a/r54-D (debug, :ai child ONLY): loads the tiny fp16/stub probe models
 * with the CHILD's own ORT and logs MODEL_PROBE_* lines to the durable child
 * log. The main process must never touch ORT (r47 native-abort law), so the
 * IAiDetector.runModelProbes() request is executed here, on the probe
 * thread — the only thread that owns ORT session creation.
 *
 * r54-D: the fp16 probe is a CONV probe (fp32 in [1,3,16,16] -> Cast ->
 * Conv(FLOAT16 weights+bias) -> Relu -> Cast -> [1,4,14,14]). Identity/Mul
 * probes cannot fail the way the real 278 MB fp16 inswapper fails; an
 * "ok" without a convolution is worthless. The op list is logged WITH the
 * result (the graph is the committed asset; its op list is pinned by
 * StubContractsTest).
 */
object ModelProbes {

    private fun fail(name: String, t: Throwable) {
        Timber.i("MODEL_PROBE_%s=fail:%s", name, t.message ?: t.javaClass.simpleName)
    }

    fun runAll(modelsDir: File) {
        Timber.i("MODEL_PROBES_BEGIN dir=%s", modelsDir.absolutePath)
        runCatching { probeFp16Conv(File(modelsDir, "probe_fp16_conv.onnx")) }
            .onFailure { fail("fp16_conv", it) }
        // r57-FIX3: installStubs() renames the asset to the REAL model's
        // file name (stub_inswapper_128.onnx -> inswapper_128_fp16.onnx),
        // so the old name was structurally unreachable — the probe could
        // only ever print "missing file". Resolves against the 256 B stub
        // today and the real 278 MB model once installed (slower, still
        // correct — debug-only probe).
        runCatching { probeInswapperStub(File(modelsDir, "inswapper_128_fp16.onnx")) }
            .onFailure { fail("inswapper_stub", it) }
        Timber.i("MODEL_PROBES_END")
    }

    private fun session(env: OrtEnvironment, f: File): OrtSession {
        val opts = OrtSession.SessionOptions()
        // r60: was env.createSession(f.readBytes(), opts) — a whole-file byte[].
        // 277,680,848 B allocation vs a 268,435,456 B growth limit: guaranteed OOM.
        // ORT opens the model from the path itself — exactly what
        // SwapTest.createSession (SwapTest.kt:242) already does. No Java byte[].
        return env.createSession(f.absolutePath, opts)
    }

    /**
     * r54-D: fp16 CONV probe. ran=true is only logged when the graph
     * executed AND the output shape is [1,4,14,14].
     */
    private fun probeFp16Conv(f: File) {
        if (!f.exists()) return fail("fp16_conv", IllegalStateException("missing file (install stubs first)"))
        val env = OrtEnvironment.getEnvironment()
        session(env, f).use { s ->
            val input = FloatBuffer.allocate(1 * 3 * 16 * 16)
            for (i in 0 until 1 * 3 * 16 * 16) input.put(((i % 13) - 6) / 13f)
            input.position(0)
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 16, 16)).use { x ->
                s.run(mapOf("x" to x)).use { out ->
                    val t = out[0] as OnnxTensor
                    val shape = t.info.shape.joinToString(",", "[", "]")
                    check(shape == "[1,4,14,14]") { "bad out shape $shape" }
                }
            }
        }
        Timber.i("MODEL_PROBE_fp16_conv=ok ops=Cast,Conv,Relu,Cast ran=true")
    }

    /**
     * The stub inswapper must depend on BOTH inputs: same target, two
     * different sources -> outputs must differ (the failure mode the stub
     * exists to catch: a silently ignored 'source').
     */
    private fun probeInswapperStub(f: File) {
        if (!f.exists()) return fail("inswapper_stub", IllegalStateException("missing file (install stubs first)"))
        val env = OrtEnvironment.getEnvironment()
        session(env, f).use { s ->
            val target = FloatBuffer.allocate(1 * 3 * 128 * 128)
            for (i in 0 until 1 * 3 * 128 * 128) target.put((i % 251) / 251f)
            target.position(0)
            fun src(v: Float) = FloatBuffer.allocate(512).also { b ->
                for (i in 0 until 512) b.put(v)
                b.position(0)
            }
            OnnxTensor.createTensor(env, target, longArrayOf(1, 3, 128, 128)).use { t ->
                OnnxTensor.createTensor(env, src(0f), longArrayOf(1, 512)).use { s0 ->
                    OnnxTensor.createTensor(env, src(2f), longArrayOf(1, 512)).use { s2 ->
                        s.run(mapOf("target" to t, "source" to s0)).use { o1 ->
                            s.run(mapOf("target" to t, "source" to s2)).use { o2 ->
                                val a = (o1[0] as OnnxTensor).floatBuffer.get(0)
                                val b = (o2[0] as OnnxTensor).floatBuffer.get(0)
                                check(a != b) { "output ignored 'source' (a=$b)" }
                            }
                        }
                    }
                }
            }
        }
        Timber.i("MODEL_PROBE_inswapper_stub=ok dual=differ")
    }
}
