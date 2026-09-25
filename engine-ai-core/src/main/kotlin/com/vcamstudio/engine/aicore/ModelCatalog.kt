package com.vcamstudio.engine.aicore

import android.content.Context
import org.json.JSONObject

/**
 * Phase 2 (owner "MODEL MANAGER + SCRFD DETECTION"): the model catalogue.
 * Shipped as an asset (assets/model_catalog.json) — models are NEVER bundled,
 * only their metadata is. Every entry carries the source URL, the exact
 * byte size and the SHA-256 the downloader verifies before the file is moved
 * out of .partial/.
 *
 * SHA-256 values are the HuggingFace LFS OIDs of the referenced files
 * (verified against the HF tree API at authoring time). An entry WITHOUT a
 * hash is downloaded but cannot be verified — the manager logs
 * MODEL_HASH_UNVERIFIED and keeps the file (used for emap.bin, whose
 * canonical host repo was disabled and no verified mirror exists yet).
 */
data class CatalogModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256Hex: String?,
    val licenseTitle: String,
    val licenseText: String,
    val sourceUrl: String,
    val purpose: String,
)

object ModelCatalog {

    fun load(context: Context): List<CatalogModel> {
        val json = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("models")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CatalogModel(
                id = o.getString("id"),
                displayName = o.optString("displayName", o.getString("id")),
                fileName = o.getString("fileName"),
                url = o.optString("url", ""),
                sizeBytes = o.optLong("sizeBytes", 0L),
                sha256Hex = if (o.has("sha256") && !o.isNull("sha256")) o.getString("sha256") else null,
                licenseTitle = o.optString("licenseTitle", "See source"),
                licenseText = o.optString("licenseText", ""),
                sourceUrl = o.optString("sourceUrl", ""),
                purpose = o.optString("purpose", ""),
            )
        }
    }
}
