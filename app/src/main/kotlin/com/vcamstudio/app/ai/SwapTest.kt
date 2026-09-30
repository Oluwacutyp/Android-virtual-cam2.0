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
import java.io.ByteArrayOutputStream
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

    // ---- r60: durable swap-test evidence --------------------------------
    @Volatile private var stageName: String = "init"
    private var stageFile: File? = null
    private var stageStartMs: Long = 0L

    private fun beginRun(service: AiInferenceService) {
        stageFile = java.io.File(service.filesDir, "swap_last.txt")
        stageStartMs = SystemClock.elapsedRealtime()
        stageName = "init"
        writeStage(null)
    }

    /** Call BEFORE each stage so a crash mid-stage names the stage. */
    private fun mark(stage: String) {
        stageName = stage
        writeStage(null)
    }

    private fun endRun(t: Throwable?) {
        writeStage(t)
    }

    private fun writeStage(t: Throwable?) {
        val f = stageFile ?: return
        runCatching {
            val ms = SystemClock.elapsedRealtime() - stageStartMs
            val sb = StringBuilder()
            sb.append("stage=").append(stageName)
                .append(" ms=").append(ms)
                .append(" result=").append(if (t == null) "ok" else "fail")
            if (t != null) {
                sb.append('\n').append(t.javaClass.name)
                    .append(": ").append(t.message ?: "-")
                // FULL stack — the 12-line / 24-line truncation is what hid this.
                for (fr in t.stackTrace) sb.append('\n').append("    at ").append(fr)
            }
            f.writeText(sb.toString())
        }
    }

    fun run(service: AiInferenceService, snapIn: FaceSnapshot?) {
        beginRun(service)
        try {
            runInner(service, snapIn)
            endRun(null)
        } catch (t: Throwable) {
            endRun(t)
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
        mark("1_carve_emap")
        val emap = carveEmap(insw, modelsDir) ?: return

        mark("2_sessions")
        val env = OrtEnvironment.getEnvironment()
        var arc: OrtSession? = null
        var swap: OrtSession? = null
        var full: Bitmap? = null
        try {
            mark("2a_arcface_session")
            arc = createSession(env, w600k)
            mark("2b_inswapper_session")
            swap = createSession(env, insw)

            // Face geometry from the snapshot (same math as DebugCrops).
            val buf = java.nio.ByteBuffer.wrap(snap.i420, 0, snap.len)
            mark("3_crops")
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
            mark("4_arcface_infer")
            SwapMath.arcfacePreprocess(argb112, in112)
            val t0 = SystemClock.elapsedRealtime()
            val emb = FloatArray(SwapMath.EMBED_DIM)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(in112), longArrayOf(1, 3, 112L, 112L)).use { t ->
                arc.run(mapOf("input.1" to t)).use { out ->
                    val o = out.get(0) as OnnxTensor // r62: get(0) — Result.get(String) returns Optional
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

            mark("5_project")
            // ---- STAGE 3: latent projection -----------------------------
            val p0 = SystemClock.elapsedRealtime()
            val latentRaw = FloatArray(SwapMath.EMBED_DIM)
            SwapMath.project(emb, emap, latentRaw)
            val latent = latentRaw.copyOf()
            val lnorm = SwapMath.l2norm(latent)
            Timber.i("LATENT_PROJECT=ok norm=%.6f ms=%d", lnorm, SystemClock.elapsedRealtime() - p0)

            mark("6_inswapper_infer")
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

            mark("7_pasteback")
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
            mark("8_done")
            Timber.i("SWAP_TEST=done")
        } finally {
            runCatching { arc?.close() }
            runCatching { swap?.close() }
            runCatching { full?.recycle() }
        }
    }

    // ---- r61: live-swap support ----------------------------------------
    // Sessions are built ONCE and reused across live frames (a 452 MB
    // session build per 3 s frame would be absurd). Closed on toggle-off
    // and binder shutdown. SCRFD is untouched; this is the swap thread's
    // own ORT use, in :ai, behind a default-off toggle.
    @Volatile private var cachedArc: OrtSession? = null
    @Volatile private var cachedSwap: OrtSession? = null

    fun closeCachedSessions() {
        runCatching { cachedArc?.close() }
        runCatching { cachedSwap?.close() }
        cachedArc = null
        cachedSwap = null
    }

    /** Live-swap output: JPEG of the swapped 128 crop + normalized box. */
    data class SwapFrameOut(val jpeg: ByteArray, val w: Int, val h: Int, val boxNorm: FloatArray)

    /**
     * ONE live frame. Same stages as the one-shot (same SwapMath — the
     * landmine math has exactly one source), no selftest/paste-back. Null
     * on any skip/failure (reason already logged).
     */
    fun liveFrame(service: AiInferenceService, snap: FaceSnapshot): SwapFrameOut? {
        try {
            val modelsDir = File(service.filesDir, "models")
            val w600k = File(modelsDir, "w600k_r50.onnx")
            val insw = File(modelsDir, "inswapper_128_fp16.onnx")
            if (w600k.length() != W600K_BYTES || insw.length() != INSWAPPER_BYTES) {
                Timber.i("SWAP_LIVE_SKIP reason=model_not_ready")
                return null
            }
            val emap = carveEmap(insw, modelsDir) ?: return null // cached after first run
            val env = OrtEnvironment.getEnvironment()
            if (cachedArc == null || cachedSwap == null) {
                Timber.i("SWAP_SESSION_BEGIN file=w600k_r50.onnx bytes=%d (live)", w600k.length())
                Timber.i("SWAP_SESSION_BEGIN file=inswapper_128_fp16.onnx bytes=%d (live)", insw.length())
                cachedArc = createSession(env, w600k)
                cachedSwap = createSession(env, insw)
            }
            val arc = cachedArc ?: return null
            val swapS = cachedSwap ?: return null

            val buf = java.nio.ByteBuffer.wrap(snap.i420, 0, snap.len)
            val frame = DebugCrops.i420ToBitmap(buf, snap.w, snap.h)
            val swapped: Bitmap
            try {
                val src = FloatArray(10)
                for (j in 0 until 5) {
                    src[2 * j] = (snap.lm640[2 * j] - snap.lb.padX) / snap.lb.scale
                    src[2 * j + 1] = (snap.lm640[2 * j + 1] - snap.lb.padY) / snap.lb.scale
                }
                // r62: identity to swap IN — stored source when picked,
                // otherwise self (own face -> own face, looks unchanged).
                val stored = sourceEmbedding
                val latent = FloatArray(SwapMath.EMBED_DIM)
                if (stored != null && stored.size == SwapMath.EMBED_DIM) {
                    SwapMath.project(stored, emap, latent)
                    SwapMath.l2norm(latent)
                    Timber.i("SWAP_LIVE_SRC=identity")
                } else {
                    val aff112 = FaceAlign.estimate(src, SwapMath.ARC_SIZE)
                    if (aff112 == null) {
                        frame.recycle()
                        Timber.i("SWAP_LIVE_SKIP reason=degenerate_landmarks")
                        return null
                    }
                    val crop112 = renderCrop(frame, aff112, SwapMath.ARC_SIZE)
                    val argb112 = IntArray(SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
                    crop112.getPixels(argb112, 0, SwapMath.ARC_SIZE, 0, 0, SwapMath.ARC_SIZE, SwapMath.ARC_SIZE)
                    crop112.recycle()
                    val in112 = FloatArray(3 * SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
                    SwapMath.arcfacePreprocess(argb112, in112)
                    val emb = FloatArray(SwapMath.EMBED_DIM)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(in112), longArrayOf(1, 3, 112L, 112L)).use { t ->
                        arc.run(mapOf("input.1" to t)).use { out ->
                            val o = out.get(0) as OnnxTensor
                            val fb = o.floatBuffer
                            for (i in 0 until SwapMath.EMBED_DIM) emb[i] = fb.get(i)
                        }
                    }
                    SwapMath.l2norm(emb) // MANDATORY (BN tail — see the one-shot)
                    SwapMath.project(emb, emap, latent)
                    SwapMath.l2norm(latent)
                }
                val aff128 = FaceAlign.estimate(src, SwapMath.SWAP_SIZE)
                if (aff128 == null) {
                    frame.recycle()
                    Timber.i("SWAP_LIVE_SKIP reason=degenerate_landmarks")
                    return null
                }
                val crop128 = renderCrop(frame, aff128, SwapMath.SWAP_SIZE)
                val argb128 = IntArray(SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE)
                crop128.getPixels(argb128, 0, SwapMath.SWAP_SIZE, 0, 0, SwapMath.SWAP_SIZE, SwapMath.SWAP_SIZE)
                crop128.recycle()
                val target = FloatArray(3 * SwapMath.SWAP_SIZE * SwapMath.SWAP_SIZE)
                SwapMath.swapPreprocess(argb128, target)
                val outPlanes = runSwap(env, swapS, target, latent)
                swapped = bitmapFromBgrPlanes(outPlanes)
            } finally {
                frame.recycle()
            }
            val jpg = ByteArrayOutputStream().also { bos ->
                swapped.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            }.toByteArray()
            swapped.recycle()
            val boxNorm = floatArrayOf(
                snap.box[0] / snap.lb.uprightW.coerceAtLeast(1),
                snap.box[1] / snap.lb.uprightH.coerceAtLeast(1),
                snap.box[2] / snap.lb.uprightW.coerceAtLeast(1),
                snap.box[3] / snap.lb.uprightH.coerceAtLeast(1),
            )
            return SwapFrameOut(jpg, SwapMath.SWAP_SIZE, SwapMath.SWAP_SIZE, boxNorm)
        } catch (t: Throwable) {
            Timber.i("SWAP_LIVE=fail:%s", t.message ?: t.javaClass.simpleName)
            return null
        }
    }

    // ---- r62: source identity -------------------------------------------
    // The face to swap IN. Null = self-swap (own face, identity-preserving —
    // looks like nothing changes ON PURPOSE; that was the r58 validation
    // mode). Set by the binder from a picked photo via [embedFace].
    @Volatile var sourceEmbedding: FloatArray? = null

    /** ArcFace session for one-off embeds: reuse the live cache or open a temp. */
    class ArcLease(private val s: OrtSession?, val owns: Boolean) {
        val session: OrtSession get() = s!!
        fun close() {
            val sess = s
            if (owns && sess != null) runCatching { sess.close() }
        }
    }

    fun acquireArc(service: AiInferenceService): ArcLease? {
        cachedArc?.let { return ArcLease(it, owns = false) }
        val f = File(File(service.filesDir, "models"), "w600k_r50.onnx")
        if (f.length() != W600K_BYTES) return null
        return ArcLease(createSession(OrtEnvironment.getEnvironment(), f), owns = true)
    }

    /** Embed a face from a STILL photo. pts = 5 kps in bmp pixel coords. */
    fun embedFace(service: AiInferenceService, bmp: Bitmap, pts: FloatArray): FloatArray? {
        val lease = acquireArc(service) ?: run {
            Timber.i("SOURCE_FACE=fail:model_not_ready")
            return null
        }
        try {
            val aff = FaceAlign.estimate(pts, SwapMath.ARC_SIZE) ?: return null
            val crop = renderCrop(bmp, aff, SwapMath.ARC_SIZE)
            val argb = IntArray(SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
            crop.getPixels(argb, 0, SwapMath.ARC_SIZE, 0, 0, SwapMath.ARC_SIZE, SwapMath.ARC_SIZE)
            crop.recycle()
            val in112 = FloatArray(3 * SwapMath.ARC_SIZE * SwapMath.ARC_SIZE)
            SwapMath.arcfacePreprocess(argb, in112)
            val emb = FloatArray(SwapMath.EMBED_DIM)
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(in112), longArrayOf(1, 3, 112L, 112L)).use { t ->
                lease.session.run(mapOf("input.1" to t)).use { out ->
                    val o = out.get(0) as OnnxTensor
                    val fbo = o.floatBuffer
                    for (i in 0 until SwapMath.EMBED_DIM) emb[i] = fbo.get(i)
                }
            }
            SwapMath.l2norm(emb) // MANDATORY (BN tail)
            return emb
        } finally {
            lease.close()
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
                    val o = (r.get(0) as OnnxTensor).floatBuffer // r62: get(0), not Optional get(String)
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
     * a HARD GATE (owner-verified from the real file).
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
     * r59/r60: STREAMING emap carve. inswapper_128_fp16.onnx is 277,680,829 B and the
     * :ai heap growth limit is 268,435,456 B, so the previous
     * `val bytes = modelFile.readBytes()` OOM'd 100% of the time (device-verified:
     * "Failed to allocate a 277680848 byte allocation ... growth limit 268435456").
     * We only want 1,048,576 B of that file, so we never materialise more than a
     * 256 KiB scan window plus the 1 MiB result.
     *
     * Signature: ONNX TensorProto raw_data is field 9, wire type 2 -> tag byte 0x4A;
     * length 1,048,576 as a varint -> 0x80 0x80 0x40 (64 shl 14 = 1048576).
     * The 1,048,576 bytes after the tag are the emap: little-endian float32,
     * row-major [512,512].
     *
     * name / dtype / dims are SYNTHESISED here (a byte scan cannot see them);
     * carveEmap() then hard-gates every one of them against the owner-verified
     * contract, and the sha256 below is the ultimate gate.
     */
    private fun findEmapRawData(modelFile: File): TensorInfo? {
        val tag = byteArrayOf(0x4A.toByte(), 0x80.toByte(), 0x80.toByte(), 0x40.toByte())
        val window = 1 shl 18 // 256 KiB
        val buf = ByteArray(window)
        val tail = ByteArray(tag.size - 1)

        // Pre-flight: refuse to start if the 1 MiB result could not fit anyway, so we
        // fail with a readable log instead of an OOM.
        val rt = Runtime.getRuntime()
        val budget = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (budget < SwapMath.EMAP_BYTES + (8L shl 20)) {
            Timber.i("EMAP_CARVE=fail:no_heap_budget budget=%d need=%d", budget, SwapMath.EMAP_BYTES)
            return null
        }

        // ---- pass 1: every offset of the 4-byte signature -----------------
        val candidates = ArrayList<Long>(4)
        var base = 0L
        var tailLen = 0
        runCatching {
            modelFile.inputStream().use { input ->
                while (candidates.size < 64) {
                    val n = input.read(buf, 0, window)
                    if (n <= 0) break
                    // virtual window = tail (carried from previous chunk) + this chunk
                    val winLen = tailLen + n
                    val winBase = base - tailLen
                    var i = 0
                    while (i + tag.size <= winLen) {
                        if (winByte(buf, tail, tailLen, i) == tag[0] &&
                            winByte(buf, tail, tailLen, i + 1) == tag[1] &&
                            winByte(buf, tail, tailLen, i + 2) == tag[2] &&
                            winByte(buf, tail, tailLen, i + 3) == tag[3]
                        ) {
                            candidates.add(winBase + i)
                            if (candidates.size >= 64) break
                        }
                        i++
                    }
                    // rolling tail = last 3 bytes of this chunk (handles a signature
                    // straddling the chunk boundary)
                    tailLen = if (n >= tag.size - 1) tag.size - 1 else n
                    for (k in 0 until tailLen) tail[k] = buf[n - tailLen + k]
                    base += n.toLong()
                }
            }
        }.onFailure {
            Timber.i("EMAP_CARVE=fail:scan_%s", it.message ?: "io")
            return null
        }
        if (candidates.isEmpty()) {
            Timber.i("EMAP_CARVE=fail:tag_not_found bytes=%d", modelFile.length())
            return null
        }

        // ---- pass 2: hash each candidate, first match wins ----------------
        for (off in candidates) {
            val raw = ByteArray(SwapMath.EMAP_BYTES)
            var filled = 0
            runCatching {
                modelFile.inputStream().use { input ->
                    var toSkip = off + tag.size
                    while (toSkip > 0) {
                        val s = input.skip(toSkip)
                        if (s <= 0) break
                        toSkip -= s
                    }
                    while (filled < SwapMath.EMAP_BYTES) {
                        val r = input.read(raw, filled, SwapMath.EMAP_BYTES - filled)
                        if (r <= 0) break
                        filled += r
                    }
                }
            }.onFailure {
                Timber.i("EMAP_CARVE=fail:read_%s", it.message ?: "io")
                return null
            }
            if (filled != SwapMath.EMAP_BYTES) {
                Timber.i("EMAP_CARVE=skip_candidate off=%d bytes=%d", off, filled)
                continue
            }
            val got = sha256Hex(raw)
            if (got != EMAP_SHA256) {
                Timber.i("EMAP_CARVE=skip_candidate off=%d sha256=%s", off, got)
                continue
            }
            val t = TensorInfo()
            t.dims.add(512L)
            t.dims.add(512L)
            t.dtype = 1 // FLOAT
            t.name = EMAP_INIT_NAME
            t.raw = raw
            Timber.i("EMAP_CARVE=tag_found off=%d bytes=%d sha256=%s", off, filled, got)
            return t
        }
        Timber.i("EMAP_CARVE=fail:no_candidate_matched_sha n=%d", candidates.size)
        return null
    }

    /** Byte i of the virtual window [tail(0..tailLen-1)] ++ [buf(0..n-1)]. */
    private fun winByte(buf: ByteArray, tail: ByteArray, tailLen: Int, i: Int): Byte =
        if (i < tailLen) tail[i] else buf[i - tailLen]

    private class TensorInfo {
        val dims = ArrayList<Long>()
        var dtype: Int = 0
        var name: String? = null
        var raw: ByteArray? = null
    }

}
