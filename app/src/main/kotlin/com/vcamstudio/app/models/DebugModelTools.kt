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

    /**
     * r60: the two big destinations become REAL 174 MB / 278 MB models once
     * downloaded. Never overwrite a file that is not already exactly the stub.
     * Assets are 256 B .. 6.4 KB, so reading them to compare is free.
     */
    fun installStubs(context: Context): Int {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        var n = 0
        for ((asset, dest) in MAPPING) {
            runCatching {
                val out = File(modelsDir, dest)
                val bytes = context.assets.open("stubs/$asset").use { it.readBytes() }
                // r60 GUARD: present and NOT already this stub => real model.
                if (out.exists() && out.length() != bytes.size.toLong()) {
                    Timber.i(
                        "MODEL_STUB_SKIP dest=%s reason=present_not_stub file=%d stub=%d",
                        dest, out.length(), bytes.size,
                    )
                    return@runCatching
                }
                out.writeBytes(bytes)
                Timber.i("MODEL_STUB_INSTALLED asset=%s -> %s (%d B)", asset, dest, out.length())
                n++
            }.onFailure {
                Timber.e(it, "MODEL_STUB_INSTALL_FAIL asset=%s", asset)
            }
        }
        return n
    }

    /**
     * r60: the ONLY thing the :ai startup auto-install needs. probe_fp16_conv.onnx
     * is not in the catalogue so export/import never carries it, and it is the file
     * whose absence triggers the auto-install — restoring IT must never touch the
     * two 452 MB models.
     */
    fun installProbeFile(context: Context): Boolean {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        return runCatching {
            val out = File(modelsDir, "probe_fp16_conv.onnx")
            val bytes = context.assets.open("stubs/probe_fp16_conv.onnx").use { it.readBytes() }
            if (out.exists() && out.length() == bytes.size.toLong()) return@runCatching true
            out.writeBytes(bytes)
            Timber.i("MODEL_PROBE_INSTALLED bytes=%d", out.length())
            true
        }.getOrDefault(false)
    }
}
