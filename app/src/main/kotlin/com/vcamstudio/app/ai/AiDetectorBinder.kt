package com.vcamstudio.app.ai

import android.os.SystemClock
import com.vcamstudio.engine.aiface.Detection
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

    private var boxCursor = 0
    private var framesDone = 0L

    private val probeThread = Thread({ probeLoop() }, "vcam-ai-probe")
    private val workerThread = Thread({ workerLoop() }, "vcam-ai-infer")

    init {
        probeThread.start()
        workerThread.start()
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
        // Hand to the r47 probe thread — session creation never rides a
        // binder thread. Duplicate requests (boot probe + client connect)
        // are deduped by path.
        offerProbe(path)
    }

    /** For the service report (`model=` line). */
    fun lastRequestedModelPath(): String? = lastRequestedPath

    override fun submitFrame(slot: Int, width: Int, height: Int, rotationDeg: Int, frameId: Long) {
        synchronized(pendingLock) {
            // Keep-only-latest: drop the older frame and release its slot so
            // main never starves.
            pending?.let { old -> frameRing?.release(old.slot) }
            pending = FrameTask(slot, width, height, rotationDeg, frameId)
            pendingLock.notifyAll()
        }
    }

    // ------------------------------------------------------------ session (probe thread)

    private val probeLock = java.lang.Object()
    private var probeTask: String? = null
    private var probeTaskIsSet = false

    private fun offerProbe(path: String?) {
        synchronized(probeLock) {
            probeTask = path
            probeTaskIsSet = true
            probeLock.notifyAll()
        }
    }

    private fun probeLoop() {
        while (running) {
            val path: String?
            synchronized(probeLock) {
                while (!probeTaskIsSet && running) {
                    probeLock.wait()
                }
                path = probeTask
                probeTask = null
                probeTaskIsSet = false
            }
            if (!running) break
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
            try {
                // The call that natively aborted in the MAIN process on two
                // ORT versions — if it aborts again, only this process dies.
                detector = ScrfdDetector(path)
                sessionActive = true
                Timber.i("AI_PROC_SESSION_OK model=%s", path)
                Timber.i("AI_CHILD_STATE state=running")
                callback?.onState(3, "running")
                service.writeReport("ok", null)
            } catch (t: Throwable) {
                Timber.e(t, "AI_PROC_SESSION_FAIL")
                Timber.i("AI_CHILD_STATE state=session-failed")
                callback?.onState(2, t.message ?: "session failed")
                service.writeReport("fail", t.message)
            } finally {
                sessionCreating = false
            }
        }
    }

    @Volatile private var sessionCreating = false

    private fun closeDetector() {
        sessionActive = false
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    // ------------------------------------------------------------ inference (worker thread)

    private fun workerLoop() {
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
        val fr = frameRing ?: return
        val br = boxRing ?: return
        val n = fr.payloadSize(task.slot).coerceAtMost(frameScratch.size)
        fr.readPayload(task.slot, frameScratch, n)
        // Release immediately — main can refill this slot while we infer.
        fr.release(task.slot)

        var preMs = 0L
        var inferMs = 0L
        var detection: Detection? = null
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
            }
            preMs = t1 - t0
        }
        framesDone++
        if (framesDone % 10L == 0L) {
            Timber.i(
                "AI_INFER_SAMPLE pre=%dms infer=%dms face=%s queued=%d",
                preMs, inferMs, (detection != null).toString(), queuedCount(),
            )
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

        val box = detection
        br.putInt(slot, AiDetectorClient.BOX_HAS_FACE, if (box != null) 1 else 0)
        if (box != null) {
            br.putFloat(slot, AiDetectorClient.BOX_X1, box.x1)
            br.putFloat(slot, AiDetectorClient.BOX_Y1, box.y1)
            br.putFloat(slot, AiDetectorClient.BOX_X2, box.x2)
            br.putFloat(slot, AiDetectorClient.BOX_Y2, box.y2)
            br.putFloat(slot, AiDetectorClient.BOX_SCORE, box.score)
            br.putLong(slot, AiDetectorClient.BOX_TS, System.currentTimeMillis())
            br.putInt(slot, AiDetectorClient.BOX_FRAME_W, task.width)
            br.putInt(slot, AiDetectorClient.BOX_FRAME_H, task.height)
        }
        br.putLong(slot, 40, preMs)   // PRE_MS
        br.putLong(slot, 48, inferMs) // INFER_MS
        br.putLong(slot, 56, task.frameId) // FRAME_ID
        br.setPayloadSize(slot, AiDetectorClient.BOX_PAYLOAD_BYTES)
        br.publish(slot, AiRing.STATE_FULL)
        callback?.onResult(task.frameId, slot, box != null, preMs, inferMs)
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
        runCatching { probeThread.join(1_000) }
        runCatching { workerThread.join(1_000) }
        closeDetector()
        frameRing?.close()
        boxRing?.close()
        frameRing = null
        boxRing = null
        Timber.i("AI_CHILD_STATE state=stopped")
    }
}
