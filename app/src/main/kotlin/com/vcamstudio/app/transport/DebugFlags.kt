package com.vcamstudio.app.transport

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * r54-F: the five debug toggles (mandated because A-E ship together — every
 * behaviour must be independently switchable at runtime so a regression can
 * be bisected WITHOUT a rebuild). File-backed Properties in the shared
 * app-private dir so the :ai CHILD reads them too (ai_fg gates the
 * foreground service, xnnpack gates the session build).
 *
 * DEFAULTS = the new behaviours are ON. An absent/unreadable file means
 * all-on, so the file only ever carries deliberate overrides.
 */
object DebugFlags {

    const val KEY_TRANSPORT_ATTACH = "transport_attach"
    const val KEY_TRANSPORT_PROBE = "transport_probe"
    const val KEY_AI_FG = "ai_fg"
    const val KEY_XNNPACK = "xnnpack"
    const val KEY_FEED = "feed"

    val ALL = listOf(
        KEY_TRANSPORT_ATTACH,
        KEY_TRANSPORT_PROBE,
        KEY_AI_FG,
        KEY_XNNPACK,
        KEY_FEED,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "transport_debug_flags.properties")

    fun isOn(ctx: Context, key: String): Boolean = runCatching {
        val f = file(ctx)
        if (!f.exists()) return true
        val p = Properties()
        f.inputStream().use { p.load(it) }
        p.getProperty(key, "1") != "0"
    }.getOrDefault(true)

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
        } + " analysis=" + analysisStr()

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
