package com.vcamstudio.app.transport

import android.content.Context
import com.vcamstudio.engine.transport.Formats
import com.vcamstudio.engine.transport.FrameMeta
import com.vcamstudio.engine.transport.TransportCapabilities
import com.vcamstudio.engine.transport.TransportRing
import com.vcamstudio.engine.transport.TransportRoute
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * r53: app-side transport owner. Allocates the ONE shared ring, serves it
 * through [TransportProvider], feeds it from the analyzer tap, detects
 * capabilities once, and answers the dump with TRANSPORT_* lines only.
 * NO device-specific code: every field is measured at runtime.
 */
object TransportManager {

    @Volatile var ring: TransportRing? = null
        private set

    @Volatile private var appContext: Context? = null

    /** Called by [TransportProvider.onCreate] (provider is manifest-declared). */
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    @Volatile private var caps: com.vcamstudio.engine.transport.TransportCaps? = null
    @Volatile var targetPackage: String? = null
    @Volatile var feeding: Boolean = false
        private set

    /** Frames published since enable (dump). */
    @Volatile var framesPublished: Long = 0L
        private set

    fun ensureRing(): TransportRing =
        ring ?: TransportRing.allocate().also {
            ring = it
            TransportProvider.install(it.sharedMemory)
        }

    fun enableFeed() {
        ensureRing()
        feeding = true
        refreshCaps()
        Timber.i("TRANSPORT_FEED_ON route=%s", caps?.route?.name?.lowercase() ?: "?")
    }

    fun disableFeed() {
        feeding = false
        Timber.i("TRANSPORT_FEED_OFF")
    }

    /**
     * Analyzer tap: copy the packed I420 payload into the ring. Runs on the
     * single CameraX analyzer thread; the copy is bounded by the ring
     * capacity and never touches the AI ring.
     */
    fun onFrame(payload: ByteBuffer, width: Int, height: Int, rotationDeg: Int, frameId: Long) {
        val r = ring ?: return
        if (!feeding) return
        val meta = FrameMeta(
            width = width,
            height = height,
            rotationDeg = rotationDeg,
            timestampNs = System.nanoTime(),
            format = Formats.I420,
        )
        if (r.writeFrame(payload, meta)) framesPublished++
    }

    // ---- capability detection --------------------------------------------

    fun refreshCaps(): com.vcamstudio.engine.transport.TransportCaps {
        val ctx = appContext ?: return TransportCapabilities.detect(
            TransportCapabilities.Probes(
                api = android.os.Build.VERSION.SDK_INT,
                root = "none", xposed = 0, vdm = "null",
                injectPerm = "denied", selinux = "unknown", videoDevs = 0,
            ),
        ).also { caps = it }
        val pm = ctx.packageManager
        val suOk = execSu("id")
        val magisk = suOk && execSu("ls /data/adb/magisk")
        val selinux = if (suOk) {
            execOut("getenforce")?.let { out ->
                when (out.trim().uppercase()) {
                    "ENFORCING" -> "Enforcing"
                    "PERMISSIVE" -> "Permissive"
                    else -> "unknown"
                }
            } ?: "unknown"
        } else {
            TransportCapabilities.selinuxFromEnforceFile(File("/sys/fs/selinux/enforce"))
        }
        val videoDevs = if (suOk) {
            execOut("ls /dev | grep -c '^video'")?.trim()?.toIntOrNull()
                ?: TransportCapabilities.countVideoDevs(File("/dev"))
        } else {
            TransportCapabilities.countVideoDevs(File("/dev"))
        }
        val xposedInstalled = listOf(
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "com.solohsu.android.edxp.manager",
        ).any { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
        val vdmAvailable = android.os.Build.VERSION.SDK_INT >= 34 && runCatching {
            ctx.getSystemService("virtual_device") != null
        }.getOrDefault(false)
        val injectGranted = runCatching {
            ctx.checkSelfPermission("android.permission.CAMERA_INJECT_EXTERNAL_CAMERA") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val capsOut = TransportCapabilities.detect(
            TransportCapabilities.Probes(
                api = android.os.Build.VERSION.SDK_INT,
                root = TransportCapabilities.classifyRoot(suOk, magisk),
                xposed = if (xposedInstalled) 1 else 0,
                vdm = if (vdmAvailable) "non-null" else "null",
                injectPerm = if (injectGranted) "granted" else "denied",
                selinux = selinux,
                videoDevs = videoDevs,
            ),
        )
        caps = capsOut
        Timber.i("%s", capsOut.capsLine())
        return capsOut
    }

    fun capsLine(): String = caps?.capsLine() ?: refreshCaps().capsLine()

    @Volatile var lastSelfTest: String = "not-run"
        private set

    /**
     * r53: one-tap in-app round-trip — the SAME call path the hook uses
     * (ContentResolver.call -> SharedMemory parcelable -> read-only map ->
     * seqlock-validated header read), exercised from our own process. Proves
     * the whole chain on ANY device, no root and no Xposed needed.
     */
    fun selfTest(ctx: Context): String {
        val res = runCatching {
            ensureRing()
            val b = ctx.contentResolver.call(
                android.net.Uri.parse("content://com.vcamstudio.app.transport"),
                "getRing", null, null,
            ) ?: error("provider returned null (authority mismatch?)")
            @Suppress("DEPRECATION")
            val sm: android.os.SharedMemory = b.getParcelable("ring")
                ?: error("no ring parcelable")
            val map = sm.mapReadOnly().order(java.nio.ByteOrder.nativeOrder())
            val magic = map.getInt(0)
            check(magic == 0x56435430) { "bad magic 0x%08x".format(magic) }
            val seq = map.getInt(36)
            val w = map.getInt(12)
            val h = map.getInt(16)
            check(seq % 2 == 0) { "seq odd (producer mid-write)" }
            check(w in 1..4096 && h in 1..4096) { "bad dims ${w}x${h}" }
            "ok ${w}x${h} seq=$seq frames=$framesPublished"
        }.fold(
            onSuccess = { "TRANSPORT_SELFTEST=ok:$it" },
            onFailure = { "TRANSPORT_SELFTEST=fail:${it.message}" },
        )
        lastSelfTest = res
        Timber.i("%s", res)
        return res
    }

    fun route(): TransportRoute = caps?.route ?: TransportRoute.NONE

    fun dumpSection(): String = buildString {
        append(capsLine())
        append("\nTRANSPORT_FEED=").append(if (feeding) "on" else "off")
        append("\nTRANSPORT_FRAMES=").append(framesPublished)
        append("\nTRANSPORT_TARGET=").append(targetPackage ?: "none")
        append("\nTRANSPORT_RING=").append(
            ring?.let { "shared-memory ready" } ?: "not-allocated",
        )
        append("\n").append(lastSelfTest)
    }

    // ---- su / exec helpers (probe-only; never device-specific) ------------

    private fun execSu(cmd: String): Boolean =
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val done = p.waitFor(2, TimeUnit.SECONDS)
            val code = if (done) p.exitValue() else -1
            p.destroy()
            code == 0
        }.getOrDefault(false)

    private fun execOut(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(2, TimeUnit.SECONDS)
        p.destroy()
        out
    }.getOrNull()
}
