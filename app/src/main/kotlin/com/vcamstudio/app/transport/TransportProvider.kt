package com.vcamstudio.app.transport

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SharedMemory

/**
 * r53: hands the transport ring's [SharedMemory] parcelable to consumer
 * processes (the Xposed hook inside the target app calls
 * ContentResolver.call). Exposed deliberately: the consumers run under
 * other uids. It serves exactly one thing — a read-only mapping of the
 * frame ring — and nothing else (no query/insert/update/delete).
 *
 * Authority: com.vcamstudio.app.transport (fixed — one app id installed
 * at a time; the hook probes this authority plus the .debug-suffixed one).
 */
class TransportProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        TransportManager.attach(context!!)
        return true
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
        const val AUTHORITY = "com.vcamstudio.app.transport"
        const val METHOD_GET_RING = "getRing"
        const val KEY_RING = "ring"

        fun install(sm: SharedMemory) {
            // The ring is installed by TransportManager; the provider picks
            // it up through TransportManager.ring (single source of truth).
        }
    }
}
