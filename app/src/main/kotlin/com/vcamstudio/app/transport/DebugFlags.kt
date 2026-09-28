package com.vcamstudio.app.transport

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * r54-F / r54.5: the runtime toggles (mandated because the round ships
 * together — every behaviour must be independently switchable at runtime so
 * a regression can be bisected WITHOUT a rebuild). File-backed Properties
 * in the shared app-private dir so the :ai CHILD reads them too (ai_fg
 * gates the foreground service, xnnpack gates the session build).
 *
 * r54.5 (owner): DEFAULTS = OFF for every opt-in behaviour (transport
 * attach/probe, :ai foreground service, XNNPACK, feed, model probes, crop
 * dump). An absent/unreadable file means all-OFF; the file only ever
 * carries deliberate "1" opt-ins.
 */
object DebugFlags {

    const val KEY_TRANSPORT_ATTACH = "transport_attach"
    const val KEY_TRANSPORT_PROBE = "transport_probe"
    const val KEY_AI_FG = "ai_fg"
    const val KEY_XNNPACK = "xnnpack"
    const val KEY_FEED = "feed"

    // r54.5: item D/E switches (owner: every opt-in behaviour default OFF).
    const val KEY_MODEL_PROBES = "model_probes"
    const val KEY_CROP_DUMP = "crop_dump"

    val ALL = listOf(
        KEY_TRANSPORT_ATTACH,
        KEY_TRANSPORT_PROBE,
        KEY_AI_FG,
        KEY_XNNPACK,
        KEY_FEED,
        KEY_MODEL_PROBES,
        KEY_CROP_DUMP,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "transport_debug_flags.properties")

    // r54.5 (owner): DEFAULT OFF — absent file / unreadable / missing key
    // all mean OFF; the file only ever carries deliberate "1" opt-ins.
    // Passive crash instrumentation (uncaught handlers, breadcrumbs, the
    // analyzer catch) is deliberately NOT gated by this file and stays
    // always-on.
    fun isOn(ctx: Context, key: String): Boolean = runCatching {
        val f = file(ctx)
        if (!f.exists()) return false
        val p = Properties()
        f.inputStream().use { p.load(it) }
        p.getProperty(key, "0") == "1"
    }.getOrDefault(false)

    fun set(ctx: Context, key: String, on: Boolean) {
        runCatching {
            val p = Properties()
            val f = file(ctx)
            if (f.exists()) f.inputStream().use { p.load(it) }
            p.setProperty(key, if (on) "1" else "0")
            f.outputStream().use { p.store(it, "r54-F debug toggles (1=on 0=off; absent=on)") }
        }
    }

    fun all(ctx: Context): Map<String, Boolean> = ALL.associateWith { isOn(ctx, it) }

    /** CONFIG_EFFECTIVE=... — the dump is self-describing (r54-F). */
    fun effectiveLine(ctx: Context, feedLive: Boolean): String =
        "CONFIG_EFFECTIVE=" + ALL.joinToString(",") {
            val v = if (it == KEY_FEED) feedLive else isOn(ctx, it)
            "$it=${if (v) "on" else "off"}"
        } + " analysis=" + analysisStr() +
            // r55: :ai persists the fill()-null reason here once DEGRADED
            // fires; absent/empty file = none. File-based so the reason
            // crosses the process boundary.
            " fill_null=" + runCatching {
                File(ctx.filesDir, "scrfd_fill_null.txt").readText().trim().ifEmpty { "none" }
            }.getOrDefault("none")

    // ---- r54.2-G2a: the ACTUAL CameraX analysis resolution (first frame) ----

    @Volatile var analysisW: Int = 0
        private set
    @Volatile var analysisH: Int = 0
        private set

    /** First frame wins; returns true only for that first call. */
    fun noteAnalysis(w: Int, h: Int): Boolean {
        if (analysisW != 0) return false
        analysisW = w
        analysisH = h
        return true
    }

    fun analysisStr(): String = if (analysisW == 0) "unknown" else "${analysisW}x${analysisH}"
}
