package com.vcamstudio.app.transport

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SharedMemory
import android.util.Log

/**
 * r53/r54: hands the transport ring's [SharedMemory] parcelable to consumer
 * processes (the Xposed hook inside the target app calls
 * ContentResolver.call). Exposed deliberately: the consumers run under
 * other uids. It serves exactly one thing — a read-only mapping of the
 * frame ring — and nothing else (no query/insert/update/delete).
 *
 * r54-A5 PERMISSION DECISION (recorded per owner mandate): the provider is
 * exported with NO android:permission ON PURPOSE — the hook runs inside
 * ARBITRARY target apps that share no signature with us, so any permission
 * gate would break route R entirely; the surface exposes exactly one
 * read-only SharedMemory (frame pixels), nothing writable, no metadata.
 * Reviewed r54; do not widen further (no grantUriPermissions).
 *
 * r54-A1/A2: onCreate runs during PROCESS START, before Application — it
 * may ONLY capture the context. Everything else (root probes, ring
 * allocation, file reads) happens on Detect / first feed enable / a
 * background dispatcher, never on the startup critical path, never on main.
 */
class TransportProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // r54-A4: the crash handler must exist BEFORE Application.onCreate
        // to capture provider-phase crashes (CrashLogger.install is
        // idempotent; the Application path stays as the second line).
        runCatching { com.vcamstudio.app.crash.CrashLogger.ensureInstalled(ctx) }
        return if (!DebugFlags.isOn(ctx, DebugFlags.KEY_TRANSPORT_ATTACH)) {
            Log.i(TAG, "TRANSPORT_BOOT=deferred reason=transport_attach=off")
            true
        } else {
            runCatching {
                TransportManager.attach(ctx)
                Log.i(TAG, "TRANSPORT_BOOT=provider_attached")
            }.onFailure { t ->
                Log.e(TAG, "TRANSPORT_BOOT=provider_failed", t)
            }.getOrDefault(false).also { ok ->
                if (!ok) Log.e(TAG, "TRANSPORT_BOOT=provider_failed(attach returned false)")
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_RING) return null
        val sm: SharedMemory = TransportManager.ring?.sharedMemory ?: return null
        return Bundle().apply { putParcelable(KEY_RING, sm) }
    }

    override fun openFile(uri: Uri, mode: String) = null
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    companion object {
        private const val TAG = "TransportProvider"
        const val AUTHORITY = "com.vcamstudio.app.transport"
        const val METHOD_GET_RING = "getRing"
        const val KEY_RING = "ring"

        fun install(sm: SharedMemory) {
            // The ring is installed by TransportManager; the provider picks
            // it up through TransportManager.ring (single source of truth).
        }
    }
}
