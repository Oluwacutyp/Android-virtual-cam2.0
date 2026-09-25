package com.vcamstudio.app.recording

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.vcamstudio.engine.output.RecordingOutput
import timber.log.Timber
import java.io.File

/**
 * Round-31 (owner "RECORDINGS INVISIBLE — WRITE TO PUBLIC MEDIASTORE"):
 * recordings land in the user-visible media library at Movies/VCamStudio —
 * never in app-private storage (unindexed by MediaStore on Android 10+,
 * which forced the owner to share-through a third-party app to see clips).
 *
 *  - API 29+: insert a MediaStore row (RELATIVE_PATH Movies/VCamStudio,
 *    IS_PENDING while the encoder writes) and hand the FD to the recorder;
 *    on stop the row is published (IS_PENDING=0) so the gallery sees it
 *    immediately.
 *  - API <29: legacy public dir Environment.getExternalStoragePublicDirectory
 *    (DIRECTORY_MOVIES)/VCamStudio, indexed afterwards via
 *    [MediaScannerConnection]. Falls back to the app-private dir when the
 *    legacy WRITE permission is not granted (still shareable in-app).
 */
object RecordingStore {
    const val SUBDIR = "VCamStudio"
    private const val NAME_PREFIX = "vcam_"

    /** New-recording target: encoder output + (29+) row Uri for publish/discard. */
    data class Target(val output: RecordingOutput, val uri: Uri?, val kind: String)

    /** One entry of the in-app library (same URIs as the system gallery). */
    data class Item(val uri: Uri, val name: String, val sizeBytes: Long, val dateAddedSec: Long)

    fun newName(): String =
        "$NAME_PREFIX${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())}.mp4"

    /** Creates the output target for a fresh recording. Throws on storage errors. */
    @Suppress("DEPRECATION")
    fun createTarget(context: Context, name: String): Target = if (Build.VERSION.SDK_INT >= 29) {
        val uri = insertRow(context, name, pending = true)
            ?: error("MediaStore rejected the insert")
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
        if (pfd == null) {
            discardPending(context, uri)
            error("could not open MediaStore descriptor")
        }
        Timber.i("REC_TARGET kind=mediastore name=%s uri=%s", name, uri)
        Target(RecordingOutput.FdOutput(pfd, name, uri), uri, "mediastore")
    } else {
        val pub = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            SUBDIR,
        )
        val granted = ContextCompat.checkSelfPermission(
            context, "android.permission.WRITE_EXTERNAL_STORAGE",
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val dir = if (granted) pub else legacyPrivateDir(context)
        if (!dir.exists()) dir.mkdirs()
        val kind = if (granted) "public-legacy" else "private-fallback"
        Timber.i("REC_TARGET kind=%s name=%s", kind, name)
        Target(RecordingOutput.FileOutput(File(dir, name)), null, kind)
    }

    private fun insertRow(context: Context, name: String, pending: Boolean): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SUBDIR)
                put(MediaStore.Video.Media.IS_PENDING, if (pending) 1 else 0)
            }
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
    }

    /** Publishes a finished recording (IS_PENDING=0) — gallery/Files see it instantly. */
    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null,
            )
        }.onFailure { Timber.e(it, "REC_PUBLISH failed uri=%s", uri) }
        Timber.i("REC_SAVED uri=%s", uri)
    }

    /** Deletes a still-pending row (failed start) so no 0-byte ghosts remain. */
    fun discardPending(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Timber.e(it, "REC_DISCARD failed uri=%s", uri) }
    }

    /** Legacy (<29) indexing after a direct file write. */
    fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
    }

    /** FileProvider content uri for legacy file targets (share intents). */
    fun shareUriFor(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "com.vcamstudio.fileprovider", file)

    /**
     * The in-app library: the SAME rows the gallery shows. API 29+ queries
     * MediaStore (RELATIVE_PATH Movies/VCamStudio + names vcam_*); legacy
     * lists the public dir. Never reads a private folder.
     */
    @Suppress("DEPRECATION")
    fun queryLibrary(context: Context): List<Item> {
        return if (Build.VERSION.SDK_INT >= 29) {
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
            )
            val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val args = arrayOf(
                "${Environment.DIRECTORY_MOVIES}/$SUBDIR/%",
                "$NAME_PREFIX%",
            )
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Item(
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id),
                                ),
                                name = c.getString(name) ?: "",
                                sizeBytes = c.getLong(size),
                                dateAddedSec = c.getLong(added),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        } else {
            val pub = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                SUBDIR,
            )
            pub.listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { Item(Uri.fromFile(it), it.name, it.length(), it.lastModified() / 1000) }
                ?: emptyList()
        }
    }

    /**
     * Mandate 3 — migration: on first boot after the fix, move any .mp4 from
     * the old app-private locations into the public library, delete the
     * private copy, and log MIGRATED_RECORDING per move. Idempotent: the
     * private folders are empty afterwards.
     */
    fun migrateExisting(context: Context): Int {
        val legacy = buildList {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?.listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".mp4") }
                ?.let { addAll(it) }
            context.filesDir
                ?.listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".mp4") }
                ?.let { addAll(it) }
        }
        if (legacy.isEmpty()) return 0
        Timber.i("MIGRATION scan found=%d", legacy.size)
        var moved = 0
        for (f in legacy) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val uri = insertRow(context, f.name, pending = false) ?: continue
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    } ?: error("openOutputStream returned null")
                    if (f.delete()) {
                        moved++
                        Timber.i("MIGRATED_RECORDING name=%s", f.name)
                    } else {
                        // Private copy kept -> would duplicate on next boot; drop the new row.
                        discardPending(context, uri)
                        error("could not delete private copy")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pubDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        SUBDIR,
                    )
                    if (!pubDir.exists()) pubDir.mkdirs()
                    val dst = File(pubDir, f.name)
                    if (f.renameTo(dst)) {
                        moved++
                        scan(context, dst)
                        Timber.i("MIGRATED_RECORDING name=%s", f.name)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "MIGRATION failed for %s (private copy kept)", f.name)
            }
        }
        Timber.i("MIGRATED_RECORDINGS count=%d", moved)
        // Tidy: only succeeds when the dir is already empty (safe no-op otherwise).
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.delete()
        return moved
    }

    private fun legacyPrivateDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
}
