package com.vcamstudio.engine.output

import android.os.ParcelFileDescriptor
import android.net.Uri
import java.io.File

/**
 * Round-31 (owner "RECORDINGS INVISIBLE — WRITE TO PUBLIC MEDIASTORE"): where
 * a recording is written. The encoder chain is output-agnostic — every
 * implementation here feeds a MediaMuxer; only the destination differs.
 *
 *  - [FdOutput]: a MediaStore row (Movies/VCamStudio on API 29+) opened
 *    through ContentResolver. The file is user-visible in the gallery and
 *    shareable by content [Uri] — no FileProvider hop, no private copy.
 *  - [FileOutput]: a plain file (API <29 public Movies dir with legacy
 *    storage, or the app-private dir as a last-resort fallback when the
 *    legacy write permission is missing).
 */
sealed interface RecordingOutput {
    val displayName: String

    /** Content [Uri] for MediaStore targets (publish/pending cleanup). */
    val uri: Uri?
        get() = null

    data class FileOutput(val file: File) : RecordingOutput {
        override val displayName: String get() = file.name
    }

    data class FdOutput(
        val pfd: ParcelFileDescriptor,
        override val displayName: String,
        override val uri: Uri,
    ) : RecordingOutput
}
