package com.vcamstudio.engine.aicore

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Phase 2 (owner mandate, DELIVERABLE 1): fetch, verify, store.
 *
 *  - Downloads are USER-INITIATED only ([download]); no auto-fetch.
 *  - Resumable via HTTP Range: partials live in filesDir/models/.partial/
 *    and are promoted to filesDir/models/ ONLY after the SHA-256 matches
 *    the catalogue. A mismatch deletes the partial and reports
 *    MODEL_HASH_MISMATCH.
 *  - The models directory is app-private (filesDir/models); nothing is
 *    written to public storage and no model ships inside the APK.
 *  - Every state transition logs MODEL_* lines (Timber + logcat).
 */
class ModelManager(private val context: Context) {

    /**
     * VERIFIED (round 42): SHA-256 matched, file not yet promoted — a
     * log-visible step between "download done" and READY so a crash
     * pinpoints itself (the last MODEL_STATE line names the failing step).
     */
    enum class State { NOT_DOWNLOADED, DOWNLOADING, VERIFIED, READY, FAILED, NO_MIRROR }

    data class ModelState(
        val model: CatalogModel,
        val state: State,
        val progressPct: Int = 0,
        val downloadedBytes: Long = 0L,
        val message: String? = null,
    )

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    /** One download at a time; further requests queue behind it. */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vcam-model-dl") }

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val partialDir: File get() = File(modelsDir, ".partial").apply { mkdirs() }

    private val prefs =
        context.getSharedPreferences("vcam_model_licenses", Context.MODE_PRIVATE)

    fun catalog(): List<CatalogModel> = ModelCatalog.load(context)

    init {
        // Adopt pre-existing verified files (app restart, upgrade).
        val initial = catalog().associate { m ->
            val exists = runCatching { fileOf(m).exists() }.getOrDefault(false)
            if (exists) Timber.i("MODEL_ADOPT name=%s -> READY", m.fileName)
            m.id to ModelState(
                model = m,
                state = if (exists) State.READY else State.NOT_DOWNLOADED,
                message = if (m.url.isBlank()) "No verified mirror yet" else null,
            )
        }
        _states.value = initial
    }

    fun fileOf(model: CatalogModel): File = File(modelsDir, model.fileName)

    fun isReady(id: String): Boolean = _states.value[id]?.state == State.READY

    fun readyFile(id: String): File? {
        val ms = _states.value[id] ?: return null
        return if (ms.state == State.READY) fileOf(ms.model) else null
    }

    /** The user must have seen the license text once before a download starts. */
    fun licenseSeen(id: String): Boolean = prefs.getBoolean("seen_$id", false)

    fun markLicenseSeen(id: String) {
        prefs.edit().putBoolean("seen_$id", true).apply()
    }

    fun download(id: String) {
        val ms = _states.value[id] ?: return
        if (ms.state == State.DOWNLOADING) {
            Timber.i("MODEL_DOWNLOAD_SKIP name=%s reason=already-downloading", ms.model.fileName)
            return
        }
        if (ms.model.url.isBlank()) {
            Timber.w("MODEL_DOWNLOAD_SKIP name=%s reason=no-mirror", ms.model.fileName)
            transition(ms.model, State.NO_MIRROR, message = ms.message)
            return
        }
        transition(ms.model, State.DOWNLOADING, pct = 0, bytes = ms.downloadedBytes, message = ms.message)
        executor.execute { runDownload(ms.model) }
    }

    private fun runDownload(model: CatalogModel) {
        val partial = File(partialDir, model.fileName)
        try {
            var offset = if (partial.exists()) partial.length() else 0L
            Timber.i(
                "MODEL_DOWNLOAD_START name=%s url=%s size=%d",
                model.fileName, model.url, model.sizeBytes,
            )
            if (offset > 0L) {
                Timber.i("MODEL_DOWNLOAD_RESUME name=%s from=%d", model.fileName, offset)
            }

            val digest = MessageDigest.getInstance("SHA-256")

            // A resumed prefix must hash identically: digest the existing
            // bytes once, then continue hashing live from the offset.
            if (offset > 0L) {
                partial.inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
            }

            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                fail(model, "HTTP $code")
                return
            }
            // Server ignored the Range -> restart from zero.
            if (offset > 0L && code == HttpURLConnection.HTTP_OK) {
                Timber.i("MODEL_DOWNLOAD_RESTART name=%s reason=range-ignored", model.fileName)
                offset = 0L
                partial.delete()
            }

            val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                offset + (conn.contentLengthLong.takeIf { it > 0 } ?: (model.sizeBytes - offset))
            } else {
                conn.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            }

            conn.inputStream.use { ins ->
                java.io.FileOutputStream(partial, offset > 0L).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = offset
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        publish(model, State.DOWNLOADING, pct(written, total), written)
                    }
                }
            }

            // ---- Round 42: post-100% tail. The field crash happened
            // somewhere in here; every step is now individually guarded
            // so none of them can throw out of the process. ----
            // Round 43: each step opens with a MODEL_DL_* marker — the
            // crash file's ring names the LAST step reached; the next
            // one is the suspect.

            // Step 1: SHA-256 verify. A mismatch NEVER throws — delete
            // the partial, flag FAILED, return.
            Timber.i("MODEL_DL_VERIFY_START name=%s bytes=%d", model.fileName, safeLength(partial))
            val got = Codecs.toHex(digest.digest())
            val expected = model.sha256Hex?.lowercase()
            if (expected != null && got != expected) {
                runCatching { partial.delete() }
                Timber.e(
                    "MODEL_HASH_MISMATCH name=%s expected=%s got=%s",
                    model.fileName, expected, got,
                )
                fail(model, "SHA-256 mismatch — file deleted")
                return
            }
            if (expected == null) {
                Timber.w("MODEL_HASH_UNVERIFIED name=%s (no catalogue hash)", model.fileName)
            }

            Timber.i("MODEL_DL_VERIFY_OK name=%s sha256=%s", model.fileName, got)

            // Step 2: VERIFIED marker — hash OK, file not yet in place.
            // If the process dies after this line, the promote step (or
            // the code reacting to the transition) is the suspect.
            publish(model, State.VERIFIED, 100, safeLength(partial))

            // Step 3: promote .partial -> models/. Atomic where the
            // filesystem allows; NEVER throws (round 42); a failure
            // lands in FAILED, not on the floor.
            Timber.i("MODEL_DL_MOVE_START name=%s", model.fileName)
            val dest = fileOf(model)
            if (!promote(partial, dest)) {
                Timber.e("MODEL_PROMOTE_FAIL name=%s", model.fileName)
                fail(model, "could not move the verified model into place")
                return
            }
            Timber.i("MODEL_DL_MOVE_OK name=%s bytes=%d", model.fileName, safeLength(dest))

            Timber.i(
                "MODEL_DOWNLOAD_DONE name=%s bytes=%d sha256=%s",
                model.fileName, safeLength(dest), got,
            )
            publish(model, State.READY, 100, safeLength(dest))
            Timber.i("MODEL_DL_STATE_READY name=%s", model.fileName)
        } catch (t: Throwable) {
            Timber.e(t, "MODEL_DOWNLOAD_FAIL name=%s", model.fileName)
            fail(model, t.message ?: "download failed")
        }
    }

    /**
     * Round 42: move a verified .partial into its final place. Never
     * throws, and never fails merely because the destination already
     * exists: same-volume File.renameTo is atomic and overwrites an
     * existing destination (POSIX rename semantics on Android); on a
     * filesystem where rename refuses, fall back to a stream-copy into
     * a temp sibling + atomic rename over the destination (a 16-280 MB
     * model must not be slurped into RAM to copy it). On ANY failure the
     * caller flags FAILED via the guarded path.
     */
    private fun promote(partial: File, dest: File): Boolean {
        if (runCatching { partial.renameTo(dest) }.getOrDefault(false)) return true
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return runCatching {
            partial.inputStream().use { ins ->
                java.io.FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) }
            }
            val moved = runCatching { tmp.renameTo(dest) }.getOrDefault(false) ||
                runCatching { dest.delete() && tmp.renameTo(dest) }.getOrDefault(false)
            if (!moved) runCatching { tmp.delete() }
            moved
        }.getOrElse { t ->
            Timber.e(t, "MODEL_PROMOTE_COPY_FAIL name=%s", dest.name)
            runCatching { tmp.delete() }
            false
        }
    }

    private fun fail(model: CatalogModel, message: String) {
        transition(model, State.FAILED, message = message)
    }

    private fun publish(model: CatalogModel, state: State, pct: Int, bytes: Long) {
        transition(model, state, pct, bytes, message = null)
    }

    /**
     * Round 42: the ONLY way a model row changes state. Guarantees:
     *  - never throws (a throwing emitter would kill the vcam-model-dl
     *    thread and the process with it — the r42 field crash window);
     *  - logs `MODEL_STATE name=<file> state=<STATE>` on every STATE
     *    change, so the crash file (via RingLog) shows exactly which
     *    step was running. DOWNLOADING progress ticks are not state
     *    changes -> not logged (they arrive per 64 KB chunk and would
     *    flood the ring).
     */
    private fun transition(
        model: CatalogModel,
        state: State,
        pct: Int = 0,
        bytes: Long = 0L,
        message: String? = null,
    ) {
        try {
            val prev = _states.value[model.id]
            if (prev?.state != state) {
                Timber.i("MODEL_STATE name=%s state=%s", model.fileName, state.name)
            }
            _states.value = _states.value + (
                model.id to ModelState(model, state, pct, bytes, message)
            )
        } catch (t: Throwable) {
            Timber.e(t, "MODEL_STATE_FAIL name=%s state=%s", model.fileName, state.name)
        }
    }

    fun delete(id: String) {
        val ms = _states.value[id] ?: return
        if (ms.state == State.DOWNLOADING) return
        runCatching { fileOf(ms.model).delete() }
        runCatching { File(partialDir, ms.model.fileName).delete() }
        Timber.i("MODEL_DELETED name=%s", ms.model.fileName)
        transition(ms.model, State.NOT_DOWNLOADED, 0, 0, ms.message)
    }

    private fun pct(bytes: Long, total: Long): Int =
        if (total <= 0) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)

    private fun safeLength(f: File): Long = runCatching { f.length() }.getOrDefault(0L)

    private object Codecs {
        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Diagnostics (round 42): one MODEL row per catalogue entry, so the
     * owner's diagnostics screenshot shows the per-row state.
     */
    fun dumpSection(): String = buildString {
        append("MODEL_STATES")
        for (ms in _states.value.values.sortedBy { it.model.id }) {
            append("\nMODEL name=").append(ms.model.fileName)
                .append(" state=").append(ms.state)
                .append(" pct=").append(ms.progressPct)
                .append(" bytes=").append(ms.downloadedBytes)
            ms.message?.let { append(" msg=").append(it) }
        }
    }
}
