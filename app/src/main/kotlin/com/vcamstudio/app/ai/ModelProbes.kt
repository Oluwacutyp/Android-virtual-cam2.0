package com.vcamstudio.app.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * r52a (debug, :ai child ONLY): loads the tiny fp16/stub probe models with
 * the CHILD's own ORT and logs MODEL_PROBE_* lines to the durable child
 * log. The main process must never touch ORT (r47 native-abort law), so
 * the IAiDetector.runModelProbes() request is executed here, on the probe
 * thread — the only thread that owns ORT session creation.
 *
 * For 439 bytes of probes this answers on device whether ORT 1.17.1 CPU
 * accepts fp16 tensors end to end and the fp16-weights pattern (what the
 * real 278 MB inswapper_128_fp16 uses) — BEFORE any large download.
 */
object ModelProbes {

    private fun fail(name: String, t: Throwable) {
        Timber.i("MODEL_PROBE_%s=fail:%s", name, t.message ?: t.javaClass.simpleName)
    }

    fun runAll(modelsDir: File) {
        Timber.i("MODEL_PROBES_BEGIN dir=%s", modelsDir.absolutePath)
        runCatching { probeFp16Io(File(modelsDir, "probe_fp16_io.onnx")) }
            .onFailure { fail("fp16_io", it) }
        runCatching { probeFp16Inner(File(modelsDir, "probe_fp16_inner.onnx")) }
            .onFailure { fail("fp16_inner", it) }
        runCatching { probeInswapperStub(File(modelsDir, "stub_inswapper_128.onnx")) }
            .onFailure { fail("inswapper_stub", it) }
        Timber.i("MODEL_PROBES_END")
    }

    private fun session(env: OrtEnvironment, f: File): OrtSession {
        val opts = OrtSession.SessionOptions()
        return env.createSession(f.readBytes(), opts)
    }

    private fun probeFp16Io(f: File) {
        if (!f.exists()) return fail("fp16_io", IllegalStateException("missing file"))
        val env = OrtEnvironment.getEnvironment()
        session(env, f).use { s ->
            val sb = ShortBuffer.wrap(shortArrayOf(0x3C00.toShort(), 0, 0x4000.toShort(), 0xBC00.toShort()))
            OnnxTensor.createTensor(env, sb, longArrayOf(1, 4), OnnxJavaType.FLOAT16).use { input ->
                s.run(mapOf("fp16_in" to input)).use { out ->
                    val t = out[0] as OnnxTensor
                    check(t.tensorInfo.type == OnnxJavaType.FLOAT16) { "output not fp16" }
                }
            }
        }
        Timber.i("MODEL_PROBE_fp16_io=ok")
    }

    private fun probeFp16Inner(f: File) {
        if (!f.exists()) return fail("fp16_inner", IllegalStateException("missing file"))
        val env = OrtEnvironment.getEnvironment()
        session(env, f).use { s ->
            val fb = FloatBuffer.wrap(floatArrayOf(1f, 2f, 3f, 4f))
            OnnxTensor.createTensor(env, fb, longArrayOf(1, 4)).use { input ->
                s.run(mapOf("x" to input)).use { out ->
                    val t = out[0] as OnnxTensor
                    check(t.floatBuffer.get(3) == 16f) { "wrong result" }
                }
            }
        }
        Timber.i("MODEL_PROBE_fp16_inner=ok")
    }

    /**
     * The stub inswapper must depend on BOTH inputs: same target, two
     * different sources -> outputs must differ (the failure mode the stub
     * exists to catch: a silently ignored 'source').
     */
    private fun probeInswapperStub(f: File) {
        if (!f.exists()) return fail("inswapper_stub", IllegalStateException("missing file"))
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
