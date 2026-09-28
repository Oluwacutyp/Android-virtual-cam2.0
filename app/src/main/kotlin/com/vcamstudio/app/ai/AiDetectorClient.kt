package com.vcamstudio.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber
import com.vcamstudio.engine.aiface.FaceBox
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 49: MAIN-process IPC client. Owns both rings, the service binding
 * and the result demux. Everything the ViewModel needs is surfaced through
 * [AiProcMonitor] (the CI-proven pattern: new app/ai symbols are referenced
 * only from inside app/ai).
 *
 * Flow: create rings -> bindService(BIND_AUTO_CREATE) -> on connect:
 * registerCallback -> attachRings -> setModel(pending). 5 Hz submit gate.
 * A FREE frame slot is filled, published FULL, then submitFrame() (oneway).
 * No free slot -> drop the frame and count it (backpressure, never a binder
 * queue). onResult: read the box out of the box ring, release the slot,
 * compute RTT from a 4-entry pending table, emit. linkToDeath reports child
 * death instantly (the r47 /proc poll stays as a backstop).
 */
class AiDetectorClient(private val context: Context) {

    companion object {
        // Geometry constants — exact numbers keep dumps comparable across rounds.
        const val FRAME_SLOTS = 2
        // r54.2-G2a: raised 1,382,400 (exact 720p — ZERO headroom, one byte
        // over tripped writePayload's require) to the 1080p I420 size — the
        // SAME treatment the transport ring got in r53.1.
        const val FRAME_PAYLOAD_BYTES = 3_110_400 // 1920*1080*3/2 — 1080p cap
        const val FRAME_SLOT_BYTES = AiRing.META_BYTES + FRAME_PAYLOAD_BYTES
        const val BOX_SLOTS = 8
        const val BOX_PAYLOAD_BYTES = 64
        const val BOX_SLOT_BYTES = AiRing.META_BYTES + BOX_PAYLOAD_BYTES
        private const val SUBMIT_INTERVAL_MS = 200L // 5 Hz
        private const val PENDING_TABLE_MAX = 4

        // Box payload field offsets (see owner mandate 2.1).
        const val BOX_HAS_FACE = 0
        const val BOX_X1 = 4
        const val BOX_Y1 = 8
        const val BOX_X2 = 12
        const val BOX_Y2 = 16
        const val BOX_SCORE = 20
        const val BOX_TS = 24
        const val BOX_FRAME_W = 32
        const val BOX_FRAME_H = 36
    }

    @Volatile private var api: IAiDetector? = null
    @Volatile private var frameRing: AiRing? = null
    @Volatile private var boxRing: AiRing? = null
    @Volatile private var pendingModelPath: String? = null
    @Volatile private var bound = false

    private var frameFile: File = File(context.filesDir, "ai_frames.ring")
    private var boxFile: File = File(context.filesDir, "ai_boxes.ring")

    /** Single producer (analyzer thread) touches this. */
    private val frameBytes = ByteArray(FRAME_PAYLOAD_BYTES)

    private val gateLock = Any()
    private var lastSubmitMs = 0L
    private var slotCursor = 0

    /** frameId -> submit wall clock (bounded at [PENDING_TABLE_MAX]). */
    private val pending = ConcurrentHashMap<Long, Long>()

    private val deathRecipient = IBinder.DeathRecipient {
        Timber.e("AI_BINDER_DIED")
        AiProcMonitor.noteBound(false)
        // Round 50-A0.2: capture the last system-memory reading BEFORE the
        // death event — LMKD vs policy-kill discrimination.
        AiProcMonitor.noteDeathContext()
        // linkToDeath fires for ANY child death (native abort, LMK kill,
        // crash) and cannot name the cause — the child log tail decides.
        AiProcMonitor.noteChildDeath("binder died (cause unnamed — read AI_PROC_CHILD_LOG)")
        closeRings()
    }

    private val callback = object : IAiDetectorCallback.Stub() {
        override fun onResult(frameId: Long, boxSlot: Int, hasFace: Boolean, preprocessMs: Long, inferMs: Long) {
            val box: FaceBox? = if (hasFace && boxSlot >= 0) {
                val br = boxRing
                if (br != null) {
                    FaceBox(
                        br.getFloat(boxSlot, BOX_X1),
                        br.getFloat(boxSlot, BOX_Y1),
                        br.getFloat(boxSlot, BOX_X2),
                        br.getFloat(boxSlot, BOX_Y2),
                        br.getFloat(boxSlot, BOX_SCORE),
                        br.getLong(boxSlot, BOX_TS),
                        br.getInt(boxSlot, BOX_FRAME_W),
                        br.getInt(boxSlot, BOX_FRAME_H),
                    )
                } else {
                    null
                }
            } else {
                null
            }
            if (boxSlot >= 0) boxRing?.release(boxSlot)
            val submitMs = pending.remove(frameId)
            val rtt = submitMs?.let { System.currentTimeMillis() - it } ?: -1L
            AiProcMonitor.noteResult(frameId, box, preprocessMs, inferMs, rtt)
        }

        // r52a ADDITIVE: keypoints in upright pixels — routed to the same
        // monitor surface the boxes use (no ring involvement).
        override fun onKps(frameId: Long, kps: FloatArray?, uprightW: Int, uprightH: Int) {
            if (kps != null) AiProcMonitor.noteRemoteKps(frameId, kps, uprightW, uprightH)
        }

        override fun onState(state: Int, detail: String?) {
            Timber.i("AI_CHILD_STATE_REPORT state=%d detail=%s", state, detail)
            // Round 50: all states route to the badge (VM maps onto
            // FaceDetectionController.Phase); state 3 also mirrors the
            // r47 AI_PROC_STATE=running.
            AiProcMonitor.noteChildState(state)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder ?: return
            val api = IAiDetector.Stub.asInterface(b)
            Timber.i("AI_CLIENT_BIND")
            runCatching { b.linkToDeath(deathRecipient, 0) }
            // Fresh rings per connect — a child restart must never see a
            // stale FULL slot from the previous incarnation.
            closeRings()
            val fr = runCatching { AiRing.create(frameFile, FRAME_SLOTS, FRAME_SLOT_BYTES) }.getOrNull()
            val br = runCatching { AiRing.create(boxFile, BOX_SLOTS, BOX_SLOT_BYTES) }.getOrNull()
            if (fr == null || br == null) {
                Timber.e("AI_FRAME_FAIL reason=ring-create")
                return
            }
            frameRing = fr
            boxRing = br
            try {
                api.registerCallback(callback)
                val ok = api.attachRings(
                    frameFile.absolutePath, FRAME_SLOT_BYTES, FRAME_SLOTS,
                    boxFile.absolutePath, BOX_SLOT_BYTES, BOX_SLOTS,
                )
                Timber.i(
                    "AI_RING_ATTACH ok=%s frames=%dx%d boxes=%dx%d",
                    ok, FRAME_SLOTS, FRAME_SLOT_BYTES, BOX_SLOTS, BOX_SLOT_BYTES,
                )
                pendingModelPath?.let { api.setModel(it) }
                this@AiDetectorClient.api = api
                bound = true
                AiProcMonitor.noteBound(true)
                // Round 50-A0.2: snapshot system memory + the child's
                // scheduler importance now (alive, bound).
                AiProcMonitor.noteBindContext(context)
            } catch (t: Throwable) {
                Timber.e(t, "AI_FRAME_FAIL reason=attach")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Round 50-A0.2: this was silent — the dump showed deaths with
            // no disconnect trace at all.
            Timber.w("AI_SERVICE_DISCONNECTED")
            bound = false
            api = null
            AiProcMonitor.noteBound(false)
        }
    }

    /** r54-B2: importance tracks the foreground app when the toggle is on. */
    @Volatile private var boundImportant = false

    fun start() {
        val intent = Intent(context, AiInferenceService::class.java)
        boundImportant =
            com.vcamstudio.app.transport.DebugFlags.isOn(
                context, com.vcamstudio.app.transport.DebugFlags.KEY_AI_FG,
            )
        val flags = if (boundImportant) {
            Context.BIND_IMPORTANT or Context.BIND_AUTO_CREATE
        } else {
            Context.BIND_AUTO_CREATE
        }
        runCatching { context.bindService(intent, conn, flags) }
            .onFailure { Timber.e(it, "AI_CLIENT_BIND_FAIL") }
    }

    @Volatile private var lastModelPath: String? = null

    fun setModelPath(path: String?) {
        pendingModelPath = path
        lastModelPath = path
        api?.setModel(path)
    }

    /** r54-C: force a session rebuild (e.g. the XNNPACK toggle changed). */
    fun reofferSession() {
        val p = lastModelPath ?: return
        api?.setModel(p)
    }

    /**
     * Called on the analyzer thread: 5 Hz gate -> free slot -> fill ->
     * publish FULL -> oneway submitFrame. No free slot -> drop + count.
     */
    fun submit(payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long, length: Int) {
        try {
            val fr = frameRing ?: return
            val now = System.currentTimeMillis()
            synchronized(gateLock) {
                if (now - lastSubmitMs < SUBMIT_INTERVAL_MS) return
                lastSubmitMs = now
            }
            val a = api ?: return
            val slot = pickFreeFrameSlot() ?: run {
                AiProcMonitor.noteFrameDropped()
                return
            }
            payload.position(0)
            payload.get(frameBytes, 0, length)
            // r54.2-G2b: oversized frames DROP (false), never throw. The
            // slot was only picked, never published — it stays FREE.
            if (!fr.writePayload(slot, frameBytes, length, width, height)) {
                AiProcMonitor.noteRingDrop(length, FRAME_PAYLOAD_BYTES, width, height)
                AiProcMonitor.noteFrameDropped()
                return
            }
            fr.setTag(slot, frameId)
            fr.publish(slot, AiRing.STATE_FULL)
            notePending(frameId, now)
            AiProcMonitor.noteFrameSubmitted()
            a.submitFrame(slot, width, height, rotationDeg, frameId)
        } catch (t: Throwable) {
            Timber.w(t, "AI_FRAME_FAIL frameId=%d", frameId)
            AiProcMonitor.noteFrameDropped()
        }
    }

    /** r52a debug: child loads + runs the fp16/stub probes (its ORT). */
    fun requestModelProbes() {
        runCatching { api?.runModelProbes() }
    }

    /** r58 debug: one-shot face-swap self-test in :ai (never per-frame). */
    fun requestSwapTest() {
        runCatching { api?.runSwapTest() }
    }

    /** r52a debug: child writes the next face's 112/128 align crops. */
    fun requestDebugCrops() {
        runCatching { api?.dumpDebugCrops() }
    }

    fun shutdown() {
        runCatching { api?.unregisterCallback(callback) }
        runCatching { context.unbindService(conn) }
        api = null
        bound = false
        AiProcMonitor.noteBound(false)
        closeRings()
    }

    // ------------------------------------------------------------ internals

    private fun pickFreeFrameSlot(): Int? {
        val fr = frameRing ?: return null
        for (i in 0 until FRAME_SLOTS) {
            val s = (slotCursor + i) % FRAME_SLOTS
            if (fr.state(s) == AiRing.STATE_FREE) {
                slotCursor = (s + 1) % FRAME_SLOTS
                return s
            }
        }
        return null
    }

    private fun notePending(frameId: Long, submitMs: Long) {
        if (pending.size >= PENDING_TABLE_MAX) {
            // Evict the oldest entry (its RTT is unmeasurable anyway).
            pending.minByOrNull { it.value }?.let { pending.remove(it.key) }
        }
        pending[frameId] = submitMs
    }

    private fun closeRings() {
        frameRing?.close()
        boxRing?.close()
        frameRing = null
        boxRing = null
    }
}
