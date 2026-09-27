package com.vcamstudio.engine.aiface

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import timber.log.Timber
import java.nio.FloatBuffer

/**
 * Phase 2: SCRFD on ONNX Runtime Mobile. Round 48 (owner): CPU-ONLY
 * execution provider — XNNPACK (former default) and NNAPI are retired as
 * candidate native-abort sites; arena + memory pattern off. The caller
 * owns preprocessing (1x3x640x640 float32, letterboxed, (x - 127.5) / 128
 * per insightface SCRFD) and postprocessing ([ScrfdPostprocess]).
 */
class ScrfdDetector(
    modelPath: String,
    useNnapi: Boolean = false,
) : AutoCloseable {

    companion object {
        // r51-B1 (one variable vs the dump-5 baseline PRE=113 + INFER=726ms
        // at intra=2): intra 2 -> 4. Checkpoint for the next dump: if
        // AI_INFER_AVG_MS does not beat the 2-thread baseline, or the child
        // dies / SCRFD_FPS drops -> revert to 2.
        const val INTRA_OP_THREADS = 4
        const val INTER_OP_THREADS = 1
    }

    // Round 50-A0: property initializers and init blocks run in SOURCE ORDER,
    // so this block runs BEFORE `env` below. r48 planted the step logs in an
    // init block that came after `env`, so the very first ORT touch
    // (OrtEnvironment.getEnvironment, which loads libonnxruntime) was
    // unlogged — a death there looks identical to "died before we started".
    init {
        // r48 (owner CHANGE 1): prove the file on disk before ORT parses it.
        // ~16.9 MB expected for scrfd_10g_bnkps.onnx; a truncated ONNX
        // protobuf aborts natively at parse time.
        val f = java.io.File(modelPath)
        Timber.i("SCRFD_MODEL_FILE exists=%s size=%d", f.exists(), if (f.exists()) f.length() else -1L)
        Timber.i("ORT_ENV_BEGIN")
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment().also {
        Timber.i("ORT_ENV_OK")
    }

    private val session: OrtSession
    private val inputName: String

    init {
        val opts = SessionOptions()
        // r48 (owner CHANGE 2): force CPU-only. No XNNPACK, no NNAPI. Both
        // are candidate native-abort sites on Adreno/ARM SoCs (XNNPACK has
        // known aborts on some big.LITTLE configurations). The [useNnapi]
        // parameter stays in the signature for API compatibility but is
        // IGNORED this round.
        runCatching { opts.setIntraOpNumThreads(INTRA_OP_THREADS) }
        runCatching { opts.setInterOpNumThreads(INTER_OP_THREADS) }
        // Disable the memory arena and memory pattern — both are optional
        // and have been linked to native aborts on some ARM hardware.
        // 1.17.1 API note (CI-verified): setEnableCpuMemArena/setEnableMemPattern
        // do NOT exist in this ORT version — addCPU(false) is the 1.17-era
        // arena lever (verified in ORT v1.17.1 OrtSession.java), and the
        // pattern goes through a session config entry (unknown keys are
        // ignored harmlessly by ORT core; cannot abort).
        runCatching { opts.addCPU(false) }
        runCatching { opts.addConfigEntry("session.enable_cpu_mem_arena", "0") }
        runCatching { opts.addConfigEntry("session.enable_mem_pattern", "0") }
        Timber.i("SCRFD_EP cpu-only arena=off memPattern=off")
        Timber.i(
            "SCRFD_THREADS intra=%d inter=%d",
            INTRA_OP_THREADS, INTER_OP_THREADS,
        )

        // r48 (owner CHANGE 3): defense wrap. A native abort escapes Kotlin
        // try/catch — but if this ever becomes catchable, we want the log.
        session = try {
            env.createSession(modelPath, opts)
        } catch (t: Throwable) {
            Timber.e(t, "SCRFD_CREATE_FAIL path=%s", modelPath)
            throw t
        }
        inputName = session.inputNames.iterator().next()
        Timber.i("ONNX_SESSION_OK ep=cpu input=%s", inputName)
        // r51-B2 (measure-only): report the model's input shape — symbolic
        // vs fixed 640 is the decision input for any future 320-input round
        // (no shape change is made here).
        val inShape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
        Timber.i("ONNX_INPUT_SHAPE shape=%s", inShape?.contentToString() ?: "unknown")
    }

    /**
     * Runs detection on a ready 1x3x640x640 CHW tensor. Returns the top
     * detection in 640x640 pixel coordinates (letterboxed), or null.
     */
    fun detectTop(chw: FloatBuffer): Detection? {
        OnnxTensor.createTensor(env, chw, longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val shapes = ArrayList<LongArray>(results.size())
                val payloads = arrayOfNulls<FloatArray>(results.size())
                var i = 0
                for (value in results) {
                    val t = value.value as? OnnxTensor ?: continue
                    val buf = t.floatBuffer
                    val arr = FloatArray(buf.remaining())
                    buf.get(arr)
                    payloads[i] = arr
                    shapes.add(t.info.shape)
                    i++
                }
                val used = payloads.copyOf(i)
                val (scores, boxes) = ScrfdPostprocess.groupOutputs(shapes) { idx -> used[idx]!! }
                return ScrfdPostprocess.decode(scores, boxes).maxByOrNull { it.score }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}
