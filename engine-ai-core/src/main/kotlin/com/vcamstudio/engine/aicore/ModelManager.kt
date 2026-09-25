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

    enum class State { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED, NO_MIRROR }

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
            m.id to ModelState(
                model = m,
                state = if (fileOf(m).exists()) State.READY else State.NOT_DOWNLOADED,
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
            _states.value = _states.value + (id to ms.copy(state = State.NO_MIRROR))
            Timber.w("MODEL_DOWNLOAD_SKIP name=%s reason=no-mirror", ms.model.fileName)
            return
        }
        _states.value = _states.value + (id to ms.copy(state = State.DOWNLOADING, progressPct = 0))
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

            val got = Codecs.toHex(digest.digest())
            val expected = model.sha256Hex?.lowercase()
            if (expected != null && got != expected) {
                partial.delete()
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

            // Promote: .partial -> models/ (same volume: atomic rename).
            if (!partial.renameTo(fileOf(model))) {
                fileOf(model).writeBytes(partial.readBytes())
                partial.delete()
            }
            Timber.i(
                "MODEL_DOWNLOAD_DONE name=%s bytes=%d sha256=%s",
                model.fileName, fileOf(model).length(), got,
            )
            publish(model, State.READY, 100, fileOf(model).length())
        } catch (t: Throwable) {
            Timber.e(t, "MODEL_DOWNLOAD_FAIL name=%s", model.fileName)
            fail(model, t.message ?: "download failed")
        }
    }

    private fun fail(model: CatalogModel, message: String) {
        _states.value = _states.value + (
            model.id to ModelState(model, State.FAILED, message = message)
        )
    }

    private fun publish(model: CatalogModel, state: State, pct: Int, bytes: Long) {
        _states.value = _states.value + (
            model.id to ModelState(model, state, pct, bytes, message = null)
        )
    }

    fun delete(id: String) {
        val ms = _states.value[id] ?: return
        if (ms.state == State.DOWNLOADING) return
        fileOf(ms.model).delete()
        File(partialDir, ms.model.fileName).delete()
        Timber.i("MODEL_DELETED name=%s", ms.model.fileName)
        _states.value = _states.value + (
            id to ModelState(ms.model, State.NOT_DOWNLOADED, 0, 0, ms.message)
        )
    }

    private fun pct(bytes: Long, total: Long): Int =
        if (total <= 0) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)

    private object Codecs {
        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}
