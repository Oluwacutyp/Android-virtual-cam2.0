package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vcamstudio.engine.aicore.ModelManager

/**
 * Phase 2 (owner DELIVERABLE 1): Settings -> AI Models. Rows come from the
 * bundled catalogue; downloads are strictly user-initiated, resumable, and
 * SHA-256-verified before the file leaves .partial/. The license text must
 * be shown once before the first download of each model (mandate).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSheet(
    states: Map<String, ModelManager.ModelState>,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    licenseSeen: (String) -> Boolean,
    onMarkLicenseSeen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var licenseFor by remember { mutableStateOf<ModelManager.ModelState?>(null) }

    fun request(ms: ModelManager.ModelState) {
        if (licenseSeen(ms.model.id)) {
            onDownload(ms.model.id)
        } else {
            licenseFor = ms
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("AI Models", style = MaterialTheme.typography.titleMedium)
            Text(
                "Private app storage (filesDir/models) — downloaded on demand, never bundled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val ordered = states.values.sortedBy { it.model.id }
            items(ordered.size) { i ->
                val ms = ordered[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ms.model.displayName, style = MaterialTheme.typography.bodyMedium)
                        val sizeLabel = if (ms.model.sizeBytes > 0) {
                            "%.1f MB".format(ms.model.sizeBytes / 1_000_000f)
                        } else {
                            "size unknown"
                        }
                        Text(
                            buildString {
                                append(sizeLabel)
                                if (ms.model.sha256Hex != null) {
                                    append("  ·  sha256 ")
                                    append(ms.model.sha256Hex!!.take(12))
                                    append("…")
                                } else {
                                    append("  ·  hash unverified")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            ms.model.purpose,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ms.message != null && ms.state != ModelManager.State.READY) {
                            Text(
                                ms.message!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    when (ms.state) {
                        ModelManager.State.READY -> {
                            Text(
                                "Ready ✓",
                                color = Color(0xFF22C55E),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            TextButton(onClick = { onDelete(ms.model.id) }) { Text("Delete") }
                        }
                        ModelManager.State.DOWNLOADING -> Text(
                            "${ms.progressPct}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        ModelManager.State.VERIFIED -> Text(
                            "Verified…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ModelManager.State.NO_MIRROR -> Text(
                            "No mirror",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ModelManager.State.FAILED -> TextButton(onClick = { request(ms) }) { Text("Retry") }
                        ModelManager.State.NOT_DOWNLOADED -> TextButton(onClick = { request(ms) }) {
                            Text("Get model")
                        }
                    }
                }
                if (i < ordered.size - 1) HorizontalDivider()
            }
            item {
                Text(
                    "License is shown once before the first download. SHA-256 is verified after every download; a mismatch deletes the file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }

    licenseFor?.let { ms ->
        AlertDialog(
            onDismissRequest = { licenseFor = null },
            title = { Text(ms.model.licenseTitle) },
            text = {
                Column {
                    Text(
                        ms.model.licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (ms.model.sourceUrl.isNotEmpty()) {
                        Text(
                            "Source: ${ms.model.sourceUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onMarkLicenseSeen(ms.model.id)
                    licenseFor = null
                    onDownload(ms.model.id)
                }) { Text("Agree & download") }
            },
            dismissButton = {
                TextButton(onClick = { licenseFor = null }) { Text("Cancel") }
            },
        )
    }
}
