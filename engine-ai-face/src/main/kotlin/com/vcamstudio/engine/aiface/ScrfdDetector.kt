package com.vcamstudio.engine.aiface

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import timber.log.Timber
import java.nio.FloatBuffer

/**
 * Phase 2: SCRFD on ONNX Runtime Mobile. XNNPACK delegate by default
 * (owner mandate); NNAPI is an opt-in dev toggle ([useNnapi]) — applied at
 * session creation. The caller owns preprocessing (1x3x640x640 float32,
 * letterboxed, (x/255 - 0.5) / 0.125... i.e. (x - 127.5) / 128 per
 * insightface SCRFD) and postprocessing ([ScrfdPostprocess]).
 */
class ScrfdDetector(
    modelPath: String,
    useNnapi: Boolean = false,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val opts = SessionOptions()
        var ep = if (useNnapi) "nnapi" else "xnnpack"
        try {
            if (useNnapi) {
                opts.addNnapi()
            } else {
                opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
            }
        } catch (t: Throwable) {
            Timber.e(t, "ONNX_EP_FAIL requested=%s -> default CPU", ep)
            ep = "cpu"
        }
        if (ep == "cpu") runCatching { opts.setIntraOpNumThreads(2) }
        session = env.createSession(modelPath, opts)
        inputName = session.inputNames.iterator().next()
        Timber.i("ONNX_SESSION_OK ep=%s input=%s", ep, inputName)
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
