package com.vcamstudio.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
        const val FRAME_PAYLOAD_BYTES = 1_382_400 // 1280*720*3/2 — 720p headroom
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
        Log.e("vcam-ai", "AI_BINDER_DIED")
        AiProcMonitor.noteBound(false)
        AiProcMonitor.noteChildDeath("binder died (native abort in :ai)")
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

        override fun onState(state: Int, detail: String?) {
            Log.i("vcam-ai", "AI_CHILD_STATE_REPORT state=%d detail=%s", state, detail)
            if (state == 3) AiProcMonitor.noteChildRunning()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder ?: return
            val api = IAiDetector.Stub.asInterface(b)
            Log.i("vcam-ai", "AI_CLIENT_BIND")
            runCatching { b.linkToDeath(deathRecipient, 0) }
            // Fresh rings per connect — a child restart must never see a
            // stale FULL slot from the previous incarnation.
            closeRings()
            val fr = runCatching { AiRing.create(frameFile, FRAME_SLOTS, FRAME_SLOT_BYTES) }.getOrNull()
            val br = runCatching { AiRing.create(boxFile, BOX_SLOTS, BOX_SLOT_BYTES) }.getOrNull()
            if (fr == null || br == null) {
                Log.e("vcam-ai", "AI_FRAME_FAIL reason=ring-create")
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
                Log.i(
                    "vcam-ai", "AI_RING_ATTACH ok=%s frames=%dx%d boxes=%dx%d",
                    ok, FRAME_SLOTS, FRAME_SLOT_BYTES, BOX_SLOTS, BOX_SLOT_BYTES,
                )
                pendingModelPath?.let { api.setModel(it) }
                this@AiDetectorClient.api = api
                bound = true
                AiProcMonitor.noteBound(true)
            } catch (t: Throwable) {
                Log.e("vcam-ai", "AI_FRAME_FAIL reason=attach", t)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            api = null
            AiProcMonitor.noteBound(false)
        }
    }

    fun start() {
        val intent = Intent(context, AiInferenceService::class.java)
        runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.e("vcam-ai", "AI_CLIENT_BIND_FAIL", it) }
    }

    fun setModelPath(path: String?) {
        pendingModelPath = path
        api?.setModel(path)
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
            fr.writePayload(slot, frameBytes, length)
            fr.setTag(slot, frameId)
            fr.publish(slot, AiRing.STATE_FULL)
            notePending(frameId, now)
            AiProcMonitor.noteFrameSubmitted()
            a.submitFrame(slot, width, height, rotationDeg, frameId)
        } catch (t: Throwable) {
            Log.w("vcam-ai", "AI_FRAME_FAIL frameId=%d", frameId, t)
            AiProcMonitor.noteFrameDropped()
        }
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
