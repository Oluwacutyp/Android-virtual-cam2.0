package com.vcamstudio.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import com.vcamstudio.engine.aiface.FaceAlign
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * r58: the ONE-SHOT face-swap pipeline (emap carve -> ArcFace latent ->
 * projection -> INSwapper -> paste-back -> self-swap gate). Runs in :ai on
 * its own thread, ONLY from the "Run swap test" button — never per-frame
 * (a 452 MB stack on CPU ORT is ~1 fps and must not touch the Phase-1
 * preview). Never throws: every stage logs ok/fail so ONE dump localises
 * the fault.
 *
 * Gates are on CATALOGUE SIZE (never file existence — the 6.4 KB / 256 B
 * stubs load fine and produce meaningless output).
 *
 * Landmine map (each has bitten this project): w600k output is "683" not
 * "fc1"; its batch dim is the literal string "None"; ArcFace range is
 * (px-127.5)/127.5 but inswapper is px/255; the 128 template is
 * ARCFACE_DST + 8.0 (NOT scaled); emap omission is a silent wrong
 * identity; inswapper output is 0..1 and fp16 is WEIGHTS-ONLY (all I/O is
 * fp32).
 */
/** Latest detected face, snapshotted by the binder worker for the one-shot. */
data class FaceSnapshot(
    val i420: ByteArray,
    val len: Int,
    val w: Int,
    val h: Int,
    val lm640: FloatArray,
    val lb: com.vcamstudio.engine.aiface.ScrfdPreprocess.Letterbox,
    val box: FloatArray,
)

object SwapTest {

    // Frozen catalogue sizes (docs/PROGRESS.md) — the stub gate.
    private const val W600K_BYTES = 174_383_860L
    private const val INSWAPPER_BYTES = 277_680_829L

    // r58 ADDENDUM (owner parsed both real files; hash-matched the
    // catalogue): the emap initializer is literally NAMED "initializer",
    // it is the ONLY FLOAT32 initializer in the file (all weights are
    // FLOAT16), and its sha256 below is the C-order row-major raw bytes
    // (tobytes(), NO transpose). All of these are HARD GATES now.
    private const val EMAP_INIT_NAME = "initializer"
    private const val EMAP_SHA256 = "370af5bf707dafdbea8a40448d697d9697610bd223ecf92887af9c9cc7055ac8"

    fun run(service: AiInferenceService, snapIn: FaceSnapshot?) {
        try {
            runInner(service, snapIn)
        } catch (t: Throwable) {
            Timber.e(t, "SWAP_TEST=fail:%s", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun runInner(service: AiInferenceService, snapIn: FaceSnapshot?) {
        val modelsDir = File(service.filesDir, "models")
        val w600k = File(modelsDir, "w600k_r50.onnx")
        val insw = File(modelsDir, "inswapper_128_fp16.onnx")

        // SCOPE GUARD: size gate — file.exists() is NOT enough (stubs).
        if (w600k.length() != W600K_BYTES) {
            Timber.i("SWAP_SKIP reason=model_not_ready name=%s bytes=%d", w600k.name, w600k.length())
            return
        }
        if (insw.length() != INSWAPPER_BYTES) {
            Timber.i("SWAP_SKIP reason=model_not_ready name=%s bytes=%d", insw.name, insw.length())
            return
        }
        val snap = snapIn
        if (snap == null) {
            Timber.i("SWAP_SKIP reason=no_face name=- bytes=0")
            return
        }

        // ---- STAGE 1: carve emap.bin ------------------------------------
        val emap = carveEmap(insw, modelsDir) ?: return

        val env = OrtEnvironment.getEnvironment()
        var arc: OrtSession? = null
        var swap: OrtSession? = null
        var full: Bitmap? = null
        try {
            arc = createSession(env, w600k)
            swap = createSession(env, insw)

            // Face geometry from the snapshot (same math as DebugCrops).
            val buf = java.nio.ByteBuffer.wrap(snap.i420, 0, snap.len)
            val frame = DebugCrops.i420ToBitmap(buf, snap.w, snap.h)
            full = frame
            val src = FloatArray(10)
            for (j in 0 until 5) {
                src[2 * j] = (snap.lm640[2 * j] - snap.lb.padX) / snap.lb.scale
                src[2 * j + 1] = (snap.lm640[2 * j + 1] - snap.lb.padY) / snap.lb.scale
            }
            val aff112 = FaceAlign.estimate(src, SwapMath.ARC_SIZE)
            val aff128 = FaceAlign.estimate(src, SwapMath.SWAP_SIZE)
            if (aff112 == null || aff128 == null) {
                Timber.i("SWAP_TEST=fail:degenerate_landmarks")
                return
            }
            val crop112 = renderCrop(frame, aff112, SwapMath.ARC_SIZE)
            val crop128 = renderCrop(frame, aff128, SwapMath.SWAP_SIZE)
            val argb112 = IntArray(SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
            crop112.getPixels(argb112, 0, SwapMath.ARC_SIZE, 0, 0, SwapMath.ARC_SIZE, SwapMath.ARC_SIZE)
            val argb128 = IntArray(SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE)
            crop128.getPixels(argb128, 0, SwapMath.SWAP_SIZE, 0, 0, SwapMath.SWAP_SIZE, SwapMath.SWAP_SIZE)

            // ---- STAGE 2: ArcFace embedding -----------------------------
            val in112 = FloatArray(3 * SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
            SwapMath.arcfacePreprocess(argb112, in112)
            val t0 = SystemClock.elapsedRealtime()
            val emb = FloatArray(SwapMath.EMBED_DIM)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(in112), longArrayOf(1, 3, 112L, 112L)).use { t ->
                arc.run(mapOf("input.1" to t)).use { out ->
                    val o = out.get("683") as OnnxTensor
                    val fb = o.floatBuffer
                    for (i in 0 until SwapMath.EMBED_DIM) emb[i] = fb.get(i)
                }
            }
            // r58 ADDENDUM: MANDATORY. w600k ends Flatten->Gemm->
            // BatchNormalization->"683", so the raw output is batch-
            // normalised and NOT unit length — without this explicit L2 the
            // latent is wrong while still looking plausible.
            val normAfter = SwapMath.l2norm(emb)
            Timber.i("ARCFACE_EMBED=ok dim=512 norm=%.6f ms=%d", normAfter, SystemClock.elapsedRealtime() - t0)

            // ---- STAGE 3: latent projection -----------------------------
            val p0 = SystemClock.elapsedRealtime()
            val latentRaw = FloatArray(SwapMath.EMBED_DIM)
            SwapMath.project(emb, emap, latentRaw)
            val latent = latentRaw.copyOf()
            val lnorm = SwapMath.l2norm(latent)
            Timber.i("LATENT_PROJECT=ok norm=%.6f ms=%d", lnorm, SystemClock.elapsedRealtime() - p0)

            // ---- STAGE 4 + 6: INSwapper self-swap + control -------------
            val target = FloatArray(3 * SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE)
            SwapMath.swapPreprocess(argb128, target)
            val control = FloatArray(SwapMath.EMBED_DIM)
            SwapMath.negate(latent, control)

            val selfOut = runSwap(env, swap, target, latent)
            val ctrlOut = runSwap(env, swap, target, control)
            val selfMae = SwapMath.maeBgr(selfOut, argb128)
            val ctrlMae = SwapMath.maeBgr(ctrlOut, argb128)
            val ratio = if (ctrlMae > 0f) selfMae / ctrlMae else -1f
            Timber.i(
                "SWAP_SELFTEST self_mae=%.2f control_mae=%.2f ratio=%.3f (pass = ratio well under 0.5)",
                selfMae, ctrlMae, ratio,
            )

            // ---- STAGE 5: paste-back ------------------------------------
            val p1 = SystemClock.elapsedRealtime()
            val swappedBitmap = bitmapFromBgrPlanes(selfOut)
            val inv = aff128.inverse()
            val box: String
            if (inv != null) {
                val composite = frame.copy(Bitmap.Config.ARGB_8888, true)
                val m = Matrix().apply {
                    setValues(
                        floatArrayOf(inv.m[0], inv.m[1], inv.m[2], inv.m[3], inv.m[4], inv.m[5], 0f, 0f, 1f),
                    )
                }
                Canvas(composite).drawBitmap(swappedBitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
                composite.recycle()
                box = "%.0f,%.0f,%.0f,%.0f".format(
                    snap.box[0], snap.box[1], snap.box[2], snap.box[3],
                )
                Timber.i("PASTE=ok box=%s ms=%d", box, SystemClock.elapsedRealtime() - p1)
            } else {
                box = "-"
                Timber.i("PASTE=fail:singular_affine")
            }
            swappedBitmap.recycle()
            crop112.recycle()
            crop128.recycle()
            Timber.i("SWAP_TEST=done")
        } finally {
            runCatching { arc?.close() }
            runCatching { swap?.close() }
            runCatching { full?.recycle() }
        }
    }

    /** STAGE 4: one inswapper run; returns clip(255*pred) as BGR planes. */
    private fun runSwap(
        env: OrtEnvironment,
        swap: OrtSession,
        target: FloatArray,
        source: FloatArray,
    ): FloatArray {
        val t0 = SystemClock.elapsedRealtime()
        val out = FloatArray(3 * SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(target), longArrayOf(1, 3, 128L, 128L)).use { t ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(source), longArrayOf(1, 512L)).use { s ->
                swap.run(mapOf("target" to t, "source" to s)).use { r ->
                    val o = (r.get("output") as OnnxTensor).floatBuffer
                    val plane = SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE
                    var mn = Float.MAX_VALUE
                    var mx = -Float.MAX_VALUE
                    for (i in 0 until 3 * plane) {
                        val v = o.get(i)
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                    }
                    // 0..1 -> 0..255, clip, then RGB -> BGR plane order.
                    for (i in 0 until plane) {
                        out[i] = (255f * o.get(2 * plane + i)).coerceIn(0f, 255f)          // B
                        out[plane + i] = (255f * o.get(plane + i)).coerceIn(0f, 255f)      // G
                        out[2 * plane + i] = (255f * o.get(i)).coerceIn(0f, 255f)          // R
                    }
                    Timber.i(
                        "SWAP_RUN=ok ms=%d out_min=%.3f out_max=%.3f",
                        SystemClock.elapsedRealtime() - t0, mn, mx,
                    )
                }
            }
        }
        return out
    }

    private fun createSession(env: OrtEnvironment, f: File): OrtSession {
        val opts = OrtSession.SessionOptions()
        runCatching { opts.addConfigEntry("session.enable_cpu_mem_arena", "0") }
        runCatching { opts.addConfigEntry("session.enable_mem_pattern", "0") }
        opts.setIntraOpNumThreads(2)
        opts.setInterOpNumThreads(1)
        // r56 lesson: the line before a native abort must name the site.
        Timber.i("SWAP_SESSION_BEGIN file=%s bytes=%d", f.name, f.length())
        return env.createSession(f.absolutePath, opts)
    }

    private fun renderCrop(frame: Bitmap, aff: FaceAlign.Affine, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setValues(floatArrayOf(aff.m[0], aff.m[1], aff.m[2], aff.m[3], aff.m[4], aff.m[5], 0f, 0f, 1f))
        }
        Canvas(out).drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun bitmapFromBgrPlanes(bgr: FloatArray): Bitmap {
        val size = SwapMath.SWAP_SIZE
        val plane = size * size
        val px = IntArray(plane)
        for (i in 0 until plane) {
            val b = bgr[i].toInt().coerceIn(0, 255)
            val g = bgr[plane + i].toInt().coerceIn(0, 255)
            val r = bgr[2 * plane + i].toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------- emap carve

    /**
     * STAGE 1: carve the UNUSED FLOAT [512,512] initializer (raw_data,
     * exactly 1,048,576 B) out of the inswapper model. Idempotent: an
     * existing right-sized emap.bin is hashed and reused. The sha256 is
     * LOGGED, never gated (no verified reference hash exists yet).
     */
    private fun carveEmap(insw: File, modelsDir: File): FloatArray? {
        val dst = File(modelsDir, "emap.bin")
        // Cached path is ALSO hard-gated: a stale/corrupt emap.bin is
        // deleted and re-carved, never silently trusted.
        if (dst.exists() && SwapMath.validEmapBytes(dst.length().toInt())) {
            val cached = sha256(dst)
            if (cached == EMAP_SHA256) {
                Timber.i("EMAP_CARVE=ok cached=true bytes=%d shape=512x512 sha256=%s", dst.length(), cached)
                return readEmap(dst)
            }
            Timber.i("EMAP_CARVE=cached_mismatch sha256=%s — re-carving", cached ?: "-")
            runCatching { dst.delete() }
        }
        val t0 = SystemClock.elapsedRealtime()
        val t = findEmapRawData(insw)
        if (t == null) {
            Timber.i("EMAP_CARVE=fail:initializer_not_found")
            return null
        }
        val raw = t.raw
        if (raw == null || !SwapMath.validEmapBytes(raw.size)) {
            Timber.i("EMAP_CARVE=fail:bad_size bytes=%d", raw?.size ?: -1)
            return null
        }
        // r58 ADDENDUM: the initializer's literal name is "initializer".
        if (t.name != EMAP_INIT_NAME) {
            Timber.i("EMAP_CARVE=fail:wrong_name name=%s", t.name ?: "-")
            return null
        }
        // HARD GATE: C-order float32 raw bytes — a mismatch means the wrong
        // initializer or the wrong byte order (owner-verified hash).
        val got = sha256Hex(raw)
        if (got != EMAP_SHA256) {
            Timber.i("EMAP_CARVE=fail:sha_mismatch expected=%s got=%s", EMAP_SHA256, got ?: "-")
            return null
        }
        runCatching {
            dst.writeBytes(raw)
        }.onFailure {
            Timber.i("EMAP_CARVE=fail:write_%s", it.message ?: "io")
            return null
        }
        Timber.i(
            "EMAP_CARVE=ok bytes=%d shape=512x512 sha256=%s ms=%d",
            raw.size, got, SystemClock.elapsedRealtime() - t0,
        )
        return readEmap(dst)
    }

    private fun readEmap(f: File): FloatArray? = runCatching {
        val bytes = f.readBytes()
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(SwapMath.EMBED_DIM * SwapMath.EMBED_DIM) { fb.get(it) }
    }.getOrNull()

    private fun sha256Hex(b: ByteArray): String = runCatching {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(b)
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun sha256(f: File): String? = runCatching {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                d.update(buf, 0, r)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Minimal ONNX protobuf walker (same wire-format approach as the
     * StubContractsTest parser, production side): ModelProto.graph=7 ->
     * GraphProto.initializer=5 -> TensorProto{dims=1 varint, data_type=2
     * varint, name=8 string, raw_data=9 bytes}. Returns raw_data of the
     * FLOAT tensor with dims [512,512], or null.
     */
    private fun findEmapRawData(modelFile: File): TensorInfo? {
        val bytes = modelFile.readBytes()
        // graph = ModelProto field 7 (length-delimited)
        var graph: Pair<Int, Int>? = null // (start, end)
        walkFields(bytes, 0, bytes.size) { field, wire, start, len ->
            if (field == 7 && wire == 2 && graph == null) graph = start to (start + len)
            false // keep walking (one top-level graph, but be safe)
        } ?: return null
        val (gs, ge) = graph ?: return null
        // each initializer = GraphProto field 5 (length-delimited).
        // r58 ADDENDUM: FLOAT32 is the robust selector (every weight in
        // this file is FLOAT16, the emap is the only fp32 initializer) —
        // no graph connectivity check needed. Name/shape/bytes/sha are
        // then ASSERTED by the caller (hard gates).
        var result: TensorInfo? = null
        walkFields(bytes, gs, ge) { field, wire, start, len ->
            if (field == 5 && wire == 2) {
                val t = parseTensor(bytes, start, start + len)
                if (t != null && t.dtype == 1 &&
                    t.dims.size == 2 && t.dims[0] == 512L && t.dims[1] == 512L &&
                    t.raw != null && t.raw!!.size == 1_048_576
                ) {
                    result = t
                    true // stop
                } else {
                    false
                }
            } else {
                false
            }
        }
        return result
    }

    private class TensorInfo {
        val dims = ArrayList<Long>()
        var dtype: Int = 0
        var name: String? = null
        var raw: ByteArray? = null
    }

    private fun parseTensor(b: ByteArray, s: Int, e: Int): TensorInfo? {
        val t = TensorInfo()
        var ok = false
        walkFields(b, s, e) { field, wire, start, len ->
            ok = true
            when (field) {
                1 -> if (wire == 0) t.dims.add(readVarint(b, start, len))
                2 -> if (wire == 0) t.dtype = readVarint(b, start, len).toInt()
                8 -> if (wire == 2 && t.name == null) t.name = String(b, start, len, Charsets.UTF_8)
                9 -> if (wire == 2 && t.raw == null) t.raw = b.copyOfRange(start, start + len)
            }
            false
        }
        return if (ok) t else null
    }

    private inline fun readVarint(b: ByteArray, s: Int, len: Int): Long {
        var v = 0L
        var shift = 0
        var i = s
        val e = s + len
        while (i < e) {
            v = v or ((b[i].toLong() and 0x7F) shl shift)
            if (b[i].toInt() and 0x80 == 0) break
            shift += 7
            i++
        }
        return v
    }

    /**
     * Generic protobuf wire walker: invokes [on] for every field; returns
     * the first non-false result or null when the walk completed. Handles
     * varint(0), fixed64(1), length-delimited(2), fixed32(5); skips groups
     * defensively (never produced by modern protoc).
     */
    private inline fun walkFields(
        b: ByteArray,
        start: Int,
        end: Int,
        on: (field: Int, wire: Int, payloadStart: Int, payloadLen: Int) -> Boolean,
    ): Boolean? {
        var i = start
        while (i < end) {
            // key varint
            var key = 0L
            var shift = 0
            while (i < end) {
                val byte = b[i].toInt() and 0xFF
                i++
                key = key or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) return null
            }
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7).toInt()
            when (wire) {
                0 -> {
                    val vs = i
                    while (i < end && (b[i].toInt() and 0x80) != 0) i++
                    i++
                    if (on(field, wire, vs, i - vs)) return true
                }
                1 -> {
                    if (i + 8 > end) return null
                    val r = on(field, wire, i, 8)
                    i += 8
                    if (r) return true
                }
                2 -> {
                    var l = 0L
                    var sh = 0
                    while (i < end) {
                        val byte = b[i].toInt() and 0xFF
                        i++
                        l = l or ((byte and 0x7F).toLong() shl sh)
                        if (byte and 0x80 == 0) break
                        sh += 7
                        if (sh > 63) return null
                    }
                    if (i + l > end) return null
                    if (on(field, wire, i, l.toInt())) return true
                    i += l.toInt()
                }
                3, 4 -> return null // groups: not produced by modern protoc
                5 -> {
                    if (i + 4 > end) return null
                    val r = on(field, wire, i, 4)
                    i += 4
                    if (r) return true
                }
                else -> return null
            }
        }
        return false
    }
}
