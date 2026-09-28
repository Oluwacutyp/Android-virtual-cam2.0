package com.vcamstudio.app.models

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * r52a: copies the stub ONNX models from debug ASSETS into
 * filesDir/models/ under the REAL catalogue filenames. The stub BYTES live
 * in app/src/debug/assets/stubs/ — a release APK carries none of them, so
 * this tool is inert there (asset open fails, action hidden behind
 * FLAG_DEBUGGABLE anyway). ModelManager.scanAdoptions() then adopts them; the
 * r52a size check flags the w600k/inswapper stubs with
 * MODEL_ADOPT_SIZE_MISMATCH (they are ~2 KB, not 174/278 MB) — that warning
 * is the point: it proves the guard works before 452 MB is spent.
 *
 * The two probe models keep their stub names (probes are loaded by name
 * from the models dir, in the :ai child, never in this process).
 */
object DebugModelTools {

    /** (asset name, destination name) pairs. */
    private val MAPPING = listOf(
        "stub_w600k_r50.onnx" to "w600k_r50.onnx",
        "stub_inswapper_128.onnx" to "inswapper_128_fp16.onnx",
        "probe_fp16_conv.onnx" to "probe_fp16_conv.onnx",
    )

    fun installStubs(context: Context): Int {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        var n = 0
        for ((asset, dest) in MAPPING) {
            runCatching {
                val out = File(modelsDir, dest)
                context.assets.open("stubs/$asset").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.i("MODEL_STUB_INSTALLED asset=%s -> %s (%d B)", asset, dest, out.length())
                n++
            }.onFailure {
                Timber.e(it, "MODEL_STUB_INSTALL_FAIL asset=%s", asset)
            }
        }
        return n
    }
}
