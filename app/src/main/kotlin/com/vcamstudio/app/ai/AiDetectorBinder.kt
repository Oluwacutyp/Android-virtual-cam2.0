package com.vcamstudio.app.ai

import android.os.SystemClock
import com.vcamstudio.engine.aiface.Detection
import com.vcamstudio.engine.aiface.FaceBox
import com.vcamstudio.engine.aiface.ScrfdDetector
import com.vcamstudio.engine.aiface.ScrfdPreprocess
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Round 49: the :ai child's implementation of IAiDetector. Owns the ORT
 * session (created on its own "vcam-ai-probe" thread, r47 — keep those log
 * lines byte-for-byte: AI_PROC_SESSION_OK model=, AI_PROC_SESSION_FAIL, and
 * the ai_proc_state.tmp report states started -> ok | fail | stopped) and
 * ONE inference worker ("vcam-ai-infer", queue capacity 1 = keep-only-latest).
 *
 * Per frame: copy the payload out of the ring -> release the slot
 * immediately (main can refill while we infer) -> preprocess ->
 * ScrfdDetector.detectTop -> write the box -> callback.onResult. If all box
 * slots are unread, recycle round-robin so the newest box always lands.
 */
class AiDetectorBinder(private val service: AiInferenceService) : IAiDetector.Stub() {

    private class FrameTask(
        val slot: Int,
        val width: Int,
        val height: Int,
        val rotationDeg: Int,
        val frameId: Long,
    )

    @Volatile private var frameRing: AiRing? = null
    @Volatile private var boxRing: AiRing? = null
    @Volatile private var detector: ScrfdDetector? = null
    @Volatile private var callback: IAiDetectorCallback? = null
    @Volatile private var lastRequestedPath: String? = null
    @Volatile private var sessionActive = false
    @Volatile private var running = true

    private val frameScratch = ByteArray(AiDetectorClient.FRAME_PAYLOAD_BYTES)
    private val i420: ByteBuffer =
        ByteBuffer.allocateDirect(AiDetectorClient.FRAME_PAYLOAD_BYTES).order(ByteOrder.nativeOrder())
    private val tensor: FloatBuffer =
        ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private val pendingLock = java.lang.Object()
    private var pending: FrameTask? = null

    // r51-B3: keep-only-latest overwrites (single binder-thread writer,
    // worker only reads). Reconciles SUBMITTED vs RUNS in the dump — the
    // A0.3 numbers (305 submitted, 81 runs, dropped=0) are coalescings.
    @Volatile private var framesCoalesced = 0L
    /** Last 20 pre/infer durations (worker thread only) — AI_*_AVG_MS. */
    private val recentPreMs = ArrayDeque<Long>()
    private val recentInferMs = ArrayDeque<Long>()

    private var boxCursor = 0
    private var framesDone = 0L

    private val probeThread = Thread({ probeLoop() }, "vcam-ai-probe")
    private val workerThread = Thread({ workerLoop() }, "vcam-ai-infer")

    // r58: ONE-SHOT swap test — its OWN thread, never per-frame.
    private val swapLock = java.lang.Object()
    private var swapTestRequested = false
    private val swapThread = Thread({ swapLoop() }, "vcam-ai-swap")

    // r58: latest detected face (raw I420 + geometry) for the one-shot.
    // Cheap copy on the worker; the swap thread builds bitmaps lazily.
    private val snapBuf = ByteArray(AiDetectorClient.FRAME_PAYLOAD_BYTES)
    private val snapLock = Any()
    @Volatile private var snapLen = 0
    @Volatile private var snapW = 0
    @Volatile private var snapH = 0
    @Volatile private var snapLm: FloatArray? = null
    @Volatile private var snapLb: ScrfdPreprocess.Letterbox? = null
    @Volatile private var snapBox: FloatArray? = null

    // Round 50-A0.3: 1 Hz heartbeat into the durable child log — three
    // dumps died with the last line at +16..+38 ms, but "last LOG line"
    // is not "time of death" (a write could fail/block). The heartbeat
    // makes time-of-death exact: the next dump's last [+Nms] heartbeat
    // pins the kill to the second.
    private val heartbeatThread = Thread({
        var tick = 0
        while (running) {
            Timber.i("AI_HEARTBEAT up=%dms tick=%d", SystemClock.elapsedRealtime(), ++tick)
            try {
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }, "vcam-ai-hb")

    init {
        probeThread.start()
        workerThread.start()
        swapThread.start()
        heartbeatThread.isDaemon = true
        heartbeatThread.start()
        Timber.i("AI_CHILD_STATE state=idle")
    }

    // ------------------------------------------------------------ IAiDetector

    override fun registerCallback(cb: IAiDetectorCallback?) {
        callback = cb
    }

    override fun unregisterCallback(cb: IAiDetectorCallback?) {
        if (callback === cb) callback = null
    }

    override fun attachRings(
        frameRingPath: String?,
        frameSlotBytes: Int,
        frameSlots: Int,
        boxRingPath: String?,
        boxSlotBytes: Int,
        boxSlots: Int,
    ): Boolean {
        val fr = runCatching {
            AiRing.open(java.io.File(frameRingPath ?: return false), frameSlots, frameSlotBytes)
        }.getOrNull() ?: return false
        val br = runCatching {
            AiRing.open(java.io.File(boxRingPath ?: return false), boxSlots, boxSlotBytes)
        }.getOrNull() ?: run {
            fr.close()
            return false
        }
        frameRing = fr
        boxRing = br
        Timber.i(
            "AI_RING_OPEN frames=%dx%d boxes=%dx%d path=%s",
            frameSlots, frameSlotBytes, boxSlots, boxSlotBytes, frameRingPath,
        )
        return true
    }

    override fun setModel(path: String?) {
        // Round 50-A0.1: split the AI_RING_OPEN -> AI_PROBE_BEGIN window —
        // this line proves the binder DELIVERED the request (the r50-A0
        // dump died with neither this nor PROBE_BEGIN in the log).
        Timber.i("AI_SETMODEL_RECV path=%s", path)
        // Hand to the r47 probe thread — session creation never rides a
        // binder thread. Duplicate requests (boot probe + client connect)
        // are deduped by path.
        offerProbe(path)
    }

    /** For the service report (`model=` line). */
    fun lastRequestedModelPath(): String? = lastRequestedPath

    override fun runModelProbes() {
        probesRequested = true
        synchronized(probeLock) { probeLock.notifyAll() }
    }

    override fun dumpDebugCrops() {
        // r54.5 (owner): item E is toggle-gated, DEFAULT OFF.
        if (!com.vcamstudio.app.transport.DebugFlags.isOn(
                service, com.vcamstudio.app.transport.DebugFlags.KEY_CROP_DUMP,
            )
        ) {
            Timber.i("AI_CROP_DUMP=off reason=toggle")
            return
        }
        // Consumed by the worker on the next frame WITH a detection.
        debugCropsRequested = true
        Timber.i("AI_CROP_DUMP_REQUESTED")
    }

    /** r58: one-shot face-swap self-test (button only — never per-frame). */
    override fun runSwapTest() {
        synchronized(swapLock) {
            swapTestRequested = true
            swapLock.notifyAll()
        }
        Timber.i("AI_SWAP_TEST_REQUESTED")
    }

    private fun swapLoop() {
        while (running) {
            val go = synchronized(swapLock) {
                while (!swapTestRequested && running) swapLock.wait()
                swapTestRequested.also { swapTestRequested = false }
            }
            if (!go || !running) break
            val snap = synchronized(snapLock) {
                val lm = snapLm
                val lb = snapLb
                val bx = snapBox
                if (snapLen == 0 || lm == null || lb == null || bx == null) {
                    null
                } else {
                    FaceSnapshot(snapBuf.copyOf(snapLen), snapLen, snapW, snapH, lm, lb, bx)
                }
            }
            runCatching { SwapTest.run(service, snap) }.onFailure {
                Timber.w(it, "AI_SWAP_TEST_FAIL")
            }
        }
    }

    override fun submitFrame(slot: Int, width: Int, height: Int, rotationDeg: Int, frameId: Long) {
        synchronized(pendingLock) {
            // Keep-only-latest: drop the older frame and release its slot so
            // main never starves.
            pending?.let { old ->
                frameRing?.release(old.slot)
                framesCoalesced++
            }
            pending = FrameTask(slot, width, height, rotationDeg, frameId)
            pendingLock.notifyAll()
        }
    }

    // ------------------------------------------------------------ session (probe thread)

    private val probeLock = java.lang.Object()
    private var probeTask: String? = null
    private var probeTaskIsSet = false
    // r52a debug: one-shot child-side model probes (probe thread owns ORT).
    @Volatile private var probesRequested = false
    // r52a debug: one-shot aligned-crop dump on the next detected face.
    @Volatile private var debugCropsRequested = false

    private fun offerProbe(path: String?) {
        synchronized(probeLock) {
            probeTask = path
            probeTaskIsSet = true
            probeLock.notifyAll()
            // r50-A0.4: proves the offer fully landed inside the monitor
            // (SETMODEL_RECV only proves the binder thread ENTERED setModel).
            Timber.i("AI_PROBE_OFFERED path=%s", path)
        }
    }

    private fun probeLoop() {
        Timber.i("AI_PROBE_THREAD_UP")
        try {
            probeLoopBody()
        } catch (t: Throwable) {
            // r50-A0.4: an uncaught exception on this thread is INVISIBLE to
            // the durable log (the default handler writes to logcat only) —
            // a dead probe thread reads exactly like a missed notify (the
            // A0.3 dump: 2 s of heartbeats + SETMODEL_RECV, no PROBE_WAKE).
            Timber.e(t, "AI_PROBE_THREAD_DIED")
        }
    }

    private fun probeLoopBody() {
        while (running) {
            val path: String?
            var runProbes = false
            synchronized(probeLock) {
                while (!probeTaskIsSet && !probesRequested && running) {
                    probeLock.wait()
                }
                path = probeTask
                probeTask = null
                probeTaskIsSet = false
                runProbes = probesRequested
                probesRequested = false
            }
            // r54.5 (owner): item D is toggle-gated, DEFAULT OFF.
            if (runProbes && !com.vcamstudio.app.transport.DebugFlags.isOn(
                    service, com.vcamstudio.app.transport.DebugFlags.KEY_MODEL_PROBES,
                )
            ) {
                Timber.i("MODEL_PROBES=off reason=toggle")
                runProbes = false
            }
            if (!running) break
            if (path != null) Timber.i("AI_PROBE_WAKE path=%s", path)
            if (runProbes) {
                // r52a debug: fp16/stub probes run HERE — the thread that
                // owns ORT session creation (isolation law).
                ModelProbes.runAll(java.io.File(service.filesDir, "models"))
                continue
            }
            if (path == null) {
                // Model removed: drop the session, stay in :ai.
                closeDetector()
                Timber.i("AI_CHILD_STATE state=model-missing")
                callback?.onState(1, "model-missing")
                service.writeReport("stopped", "model-removed")
                continue
            }
            if (path == lastRequestedPath && (sessionActive || sessionCreating)) continue
            lastRequestedPath = path
            sessionCreating = true
            closeDetector()
            // r54.3-H3: exact file facts on EVERY load attempt — a stub file,
            // truncated download or wrong file is visible in the dump without
            // the child ever dying.
            com.vcamstudio.app.crash.PhaseMark.markAi(service, "MODEL_FILE_RESOLVE")
            val mFile = java.io.File(path)
            val mSize = runCatching { mFile.length() }.getOrDefault(-1L)
            Timber.i("MODEL_FILE=%s MODEL_SIZE=%dB exists=%s", path, mSize, mFile.exists())
            try {
                // Round 50-A0: name the attempt — with the detector's own
                // step lines, this pins a silent child death to either
                // "before construction" or a specific constructor step.
                Timber.i("AI_PROBE_BEGIN path=%s", path)
                // r54.3-H2: everything from here to SESSION_OK is bracketed
                // by this breadcrumb (file resolve + OrtSession creation
                // happen inside the detector constructor).
                com.vcamstudio.app.crash.PhaseMark.markAi(service, "SESSION_CREATE")
                // The call that natively aborted in the MAIN process on two
                // ORT versions — if it aborts again, only this process dies.
                // r54-C/F: the XNNPACK toggle is read at session BUILD —
                // flipping it triggers a re-offer (session rebuild), no rebuild
                // of the app needed.
                detector = ScrfdDetector(
                    path,
                    useNnapi = false,
                    useXnnpack = com.vcamstudio.app.transport.DebugFlags.isOn(
                        service, com.vcamstudio.app.transport.DebugFlags.KEY_XNNPACK,
                    ),
                )
                sessionActive = true
                Timber.i("AI_PROC_SESSION_OK model=%s", path)
                Timber.i("AI_CHILD_STATE state=running")
                callback?.onState(3, "running")
                service.writeReport("ok", null)
            } catch (t: Throwable) {
                // r54.3-H3: NAMED failure — path, bytes, OrtException
                // message. The AI chain stays DOWN; the child lives.
                Timber.e(t, "AI_MODEL_LOAD_FAIL path=%s size=%dB", path, mSize)
                Timber.i("AI_CHILD_STATE state=session-failed")
                callback?.onState(2, t.message ?: "session failed")
                service.writeReport("fail", t.message)
            } finally {
                sessionCreating = false
            }
        }
    }

    @Volatile private var sessionCreating = false

    // r54.3-H2: once-only :ai breadcrumbs (worker thread writes them).
    @Volatile private var markedFirstInfer = false
    @Volatile private var markedKps = false
    @Volatile private var markedPublish = false

    // r55: fill()-null is no longer swallowed silently. Consecutive
    // failures are counted; SCRFD_FILL_FAIL logs at most once per second;
    // 10 in a row flips the state to DEGRADED (dump + badge) instead of
    // leaving RUNNING while nothing is detected.
    private var fillFailStreak = 0
    private var lastFillFailLogMs = 0L
    @Volatile private var degradedNotified = false

    /** r55: a real inference is never 0 ms (checked on sampled frames). */
    private var noopStreak = 0
    private var noopLogged = false

    private fun closeDetector() {
        sessionActive = false
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    // ------------------------------------------------------------ inference (worker thread)

    private fun workerLoop() {
        Timber.i("AI_WORKER_THREAD_UP")
        try {
            workerLoopBody()
        } catch (t: Throwable) {
            // r50-A0.4: same invisible-death gap as the probe thread.
            Timber.e(t, "AI_WORKER_THREAD_DIED")
        }
    }

    private fun workerLoopBody() {
        while (running) {
            val task: FrameTask = synchronized(pendingLock) {
                while (pending == null && running) {
                    pendingLock.wait()
                }
                val t = pending
                pending = null
                t
            } ?: break
            runCatching { processFrame(task) }.onFailure { t ->
                Timber.w(t, "AI_FRAME_FAIL child frameId=%d", task.frameId)
            }
        }
    }

    private fun queuedCount(): Int = synchronized(pendingLock) { if (pending != null) 1 else 0 }

    private fun processFrame(task: FrameTask) {

        // r54.3-H2: first inference begins (once — same code path after).
        if (!markedFirstInfer) { markedFirstInfer = true; com.vcamstudio.app.crash.PhaseMark.markAi(service, "FIRST_INFER") }
        val fr = frameRing ?: return
        val br = boxRing ?: return
        val n = fr.payloadSize(task.slot).coerceAtMost(frameScratch.size)
        fr.readPayload(task.slot, frameScratch, n)
        // Release immediately — main can refill this slot while we infer.
        fr.release(task.slot)

        var preMs = 0L
        var inferMs = 0L
        var detection: Detection? = null
        var letterbox: ScrfdPreprocess.Letterbox? = null // kept for the box conversion
        val det = detector
        if (det != null && n > 0) {
            i420.clear()
            i420.put(frameScratch, 0, n)
            i420.position(0)
            val t0 = SystemClock.elapsedRealtime()
            val lb = ScrfdPreprocess.fill(i420, task.width, task.height, task.rotationDeg, tensor)
            val t1 = SystemClock.elapsedRealtime()
            if (lb != null) {
                detection = det.detectTop(tensor)
                inferMs = SystemClock.elapsedRealtime() - t1
                letterbox = lb
                fillFailStreak = 0
            } else {
                // r55: the null is now VISIBLE — count it, log <=1/s, and
                // after 10 consecutive failures report DEGRADED (state 4)
                // + persist the reason for CONFIG_EFFECTIVE.
                fillFailStreak++
                val reason = ScrfdPreprocess.lastNullReason ?: "unknown"
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastFillFailLogMs >= 1000L) {
                    lastFillFailLogMs = nowMs
                    Timber.i("SCRFD_FILL_FAIL reason=%s count=%d", reason, fillFailStreak)
                }
                if (fillFailStreak >= 10 && !degradedNotified) {
                    degradedNotified = true
                    Timber.i("SCRFD_STATE=DEGRADED reason=fill_null")
                    runCatching { callback?.onState(4, "fill_null:$reason") }
                    service.writeReport("degraded", "fill_null:$reason")
                    runCatching {
                        java.io.File(service.filesDir, "scrfd_fill_null.txt").writeText(reason)
                    }
                }
            }
            preMs = t1 - t0
            // r55: no-op sanity — zero ms for 20 consecutive frames means
            // no inference work happened at all.
            if (inferMs == 0L) {
                noopStreak++
                if (noopStreak >= 20 && !noopLogged) {
                    noopLogged = true
                    Timber.i("SCRFD_SUSPECT_NOOP frames=%d", noopStreak)
                }
            } else {
                noopStreak = 0
            }
        }
        framesDone++
        if (recentPreMs.size >= 20) recentPreMs.removeFirst()
        recentPreMs.addLast(preMs)
        if (recentInferMs.size >= 20) recentInferMs.removeFirst()
        recentInferMs.addLast(inferMs)
        if (framesDone % 10L == 0L) {
            // r51: raw pre-clamp detection + letterbox params so the dump
            // shows the geometry BEFORE toFaceBox normalization.
            Timber.i(
                "AI_INFER_SAMPLE pre=%dms infer=%dms face=%s queued=%d coalesced=%d frame=%dx%d rot=%d upright=%dx%d pad=%.1f,%.1f scale=%.3f det=[%.0f,%.0f,%.0f,%.0f] score=%.2f",
                preMs, inferMs, (detection != null).toString(), queuedCount(), framesCoalesced,
                task.width, task.height, task.rotationDeg,
                letterbox?.uprightW ?: 0, letterbox?.uprightH ?: 0,
                letterbox?.padX ?: 0f, letterbox?.padY ?: 0f, letterbox?.scale ?: 0f,
                detection?.x1 ?: 0f, detection?.y1 ?: 0f, detection?.x2 ?: 0f, detection?.y2 ?: 0f,
                detection?.score ?: 0f,
            )
            Timber.i("AI_PRE_AVG_MS=%d n=%d", recentPreMs.average().toLong(), recentPreMs.size)
            Timber.i("AI_INFER_AVG_MS=%d n=%d", recentInferMs.average().toLong(), recentInferMs.size)
        }

        // Box ring: prefer a FREE slot; recycle round-robin when all unread —
        // the newest box always lands.
        var slot = -1
        for (i in 0 until AiDetectorClient.BOX_SLOTS) {
            val s = (boxCursor + i) % AiDetectorClient.BOX_SLOTS
            if (br.state(s) == AiRing.STATE_FREE) {
                slot = s
                break
            }
        }
        if (slot < 0) {
            slot = boxCursor % AiDetectorClient.BOX_SLOTS
        }
        boxCursor = (slot + 1) % AiDetectorClient.BOX_SLOTS

        // Round 50: the ring carries an UPRIGHT, NORMALIZED FaceBox. The raw
        // Detection is in 640x640 letterbox pixels (unclamped), and both
        // FaceDebugOverlay (x * srcW * scale) and the VM front-mirror
        // (1f - x2) assume normalized coordinates. toFaceBox clamps to
        // [0,1], so a partly out-of-frame face hugs the edge instead of
        // landing off-canvas.
        val box: FaceBox? = if (detection != null && letterbox != null) {
            ScrfdPreprocess.toFaceBox(detection, letterbox, System.currentTimeMillis())
        } else {
            null
        }
        br.putInt(slot, AiDetectorClient.BOX_HAS_FACE, if (box != null) 1 else 0)
        if (box != null) {
            br.putFloat(slot, AiDetectorClient.BOX_X1, box.x1)
            br.putFloat(slot, AiDetectorClient.BOX_Y1, box.y1)
            br.putFloat(slot, AiDetectorClient.BOX_X2, box.x2)
            br.putFloat(slot, AiDetectorClient.BOX_Y2, box.y2)
            br.putFloat(slot, AiDetectorClient.BOX_SCORE, box.score)
            br.putLong(slot, AiDetectorClient.BOX_TS, box.timestampMs)
            // Upright dims (letterbox.uprightW/H) — NOT task.width/height.
            br.putInt(slot, AiDetectorClient.BOX_FRAME_W, box.frameWidth)
            br.putInt(slot, AiDetectorClient.BOX_FRAME_H, box.frameHeight)
        }
        br.putLong(slot, 40, preMs)   // PRE_MS
        br.putLong(slot, 48, inferMs) // INFER_MS
        br.putLong(slot, 56, task.frameId) // FRAME_ID
        br.setPayloadSize(slot, AiDetectorClient.BOX_PAYLOAD_BYTES)
        // r54.3-H2: box handoff to main (once).
        if (!markedPublish) { markedPublish = true; com.vcamstudio.app.crash.PhaseMark.markAi(service, "RESULT_PUBLISH") }
        br.publish(slot, AiRing.STATE_FULL)
        callback?.onResult(task.frameId, slot, box != null, preMs, inferMs)
        // r54.3-H2: the r52a keypoint decode is a PRIME SUSPECT for the
        // :ai deaths — bracket it (once).
        if (!markedKps) { markedKps = true; com.vcamstudio.app.crash.PhaseMark.markAi(service, "KPS_DECODE") }
        // r52a ADDITIVE: keypoints ride the binder (boxes ring stays 8x64).
        val lm640 = detection?.landmarks
        val lb2 = letterbox
        if (box != null && lm640 != null && lb2 != null && lb2.scale > 0f) {
            val px = FloatArray(10)
            for (j in 0 until 5) {
                px[2 * j] = (lm640[2 * j] - lb2.padX) / lb2.scale
                px[2 * j + 1] = (lm640[2 * j + 1] - lb2.padY) / lb2.scale
            }
            runCatching { callback?.onKps(task.frameId, px, lb2.uprightW, lb2.uprightH) }
        }
        if (debugCropsRequested && box != null && lm640 != null && lb2 != null) {
            debugCropsRequested = false
            DebugCrops.dumpAlignCrops(service, i420, task.width, task.height, lm640, lb2)
        }
        // r58: keep the latest face for the one-shot swap test.
        if (box != null && lm640 != null && lb2 != null && lb2.scale > 0f) {
            synchronized(snapLock) {
                val cn = minOf(n, snapBuf.size)
                System.arraycopy(frameScratch, 0, snapBuf, 0, cn)
                snapLen = cn
                snapW = task.width
                snapH = task.height
                snapLm = lm640.copyOf()
                snapLb = lb2
                snapBox = floatArrayOf(
                    box.x1 * lb2.uprightW, box.y1 * lb2.uprightH,
                    box.x2 * lb2.uprightW, box.y2 * lb2.uprightH,
                )
            }
        }
    }

    // ------------------------------------------------------------ teardown

    fun shutdown() {
        running = false
        synchronized(probeLock) { probeLock.notifyAll() }
        synchronized(pendingLock) {
            pending?.let { frameRing?.release(it.slot) }
            pending = null
            pendingLock.notifyAll()
        }
        runCatching { heartbeatThread.interrupt() }
        runCatching { heartbeatThread.join(500) }
        runCatching { probeThread.join(1_000) }
        runCatching { workerThread.join(1_000) }
        synchronized(swapLock) { swapLock.notifyAll() }
        runCatching { swapThread.join(1_000) }
        closeDetector()
        frameRing?.close()
        boxRing?.close()
        frameRing = null
        boxRing = null
        Timber.i("AI_CHILD_STATE state=stopped")
    }
}
