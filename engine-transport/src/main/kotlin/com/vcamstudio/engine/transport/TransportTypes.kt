package com.vcamstudio.engine.transport

/**
 * r53 TRANSPORT v0: cross-app frame transport. Everything here is
 * capability-driven — no device model strings, no manufacturer strings, no
 * hardcoded target-app list, no hardcoded resolution/framerate. The frame
 * meta travels WITH the frame and consumers adapt.
 */

/** Per-frame metadata; the ring carries it in its header block. */
data class FrameMeta(
    val width: Int,
    val height: Int,
    val rotationDeg: Int,
    val timestampNs: Long,
    /** 1 = packed I420 (see [Formats]). */
    val format: Int,
)

object Formats {
    const val I420 = 1
    const val NV21 = 2
}

/** Routes, in preference order (root covers more apps). */
enum class TransportRoute {
    NONE,
    ROOT_HOOK,
    VIRTUALDEVICE,
}

/** The one contract both routes implement. */
interface TransportSink {
    fun open(): Boolean
    fun writeFrame(payload: java.nio.ByteBuffer, meta: FrameMeta): Boolean
    fun close()
}

/** Static capability snapshot — the fields of the TRANSPORT_CAPS line. */
data class TransportCaps(
    val api: Int,
    val root: String,          // none | su | magisk
    val xposed: Int,           // 0 | 1 (manager-installed heuristic)
    val vdm: String,           // null | non-null
    val injectPerm: String,    // granted | denied
    val selinux: String,       // Enforcing | Permissive | unknown
    val videoDevs: Int,
    val route: TransportRoute,
    val reason: String,
) {
    /** Exact line format (byte-for-byte contract, owner mandate). */
    fun capsLine(): String =
        "TRANSPORT_CAPS api=$api root=$root xposed=$xposed vdm=$vdm " +
            "inject_perm=$injectPerm selinux=$selinux video_devs=$videoDevs " +
            "route=${route.name.lowercase()} reason=$reason"
}
