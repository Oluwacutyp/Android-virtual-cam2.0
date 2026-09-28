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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
    /** Round 44: DEV SCRFD session toggle — drives the "Ready (not active)" label. */
    scrfdActive: Boolean,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    licenseSeen: (String) -> Boolean,
    onMarkLicenseSeen: (String) -> Unit,
    onDismiss: () -> Unit,
    // r52a additions
    isDebug: Boolean = false,
    meteredBlocked: String? = null,
    onMeteredProceed: () -> Unit = {},
    onMeteredDismiss: () -> Unit = {},
    onInstallStubs: () -> Unit = {},
    onRunProbes: () -> Unit = {},
    onDumpCrops: () -> Unit = {},
    onBackup: (Uri) -> Unit = {},
    onRestore: (Uri) -> Unit = {},
    // r53 transport
    transportCaps: String = "",
    transportTarget: String? = null,
    transportFeedOn: Boolean = false,
    onSetTransportFeed: (Boolean) -> Unit = {},
    onSetTransportTarget: (String?) -> Unit = {},
    onLaunchThrough: () -> Unit = {},
    onRefreshTransportCaps: () -> Unit = {},
    onSelfTest: () -> Unit = {},
) {
    val context = LocalContext.current
    // r53: installed apps holding CAMERA permission (runtime choice, no
    // hardcoded list). Excludes this app.
    val cameraApps = remember {
        val pm = context.packageManager
        runCatching {
            pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                .filter { p ->
                    p.requestedPermissions?.contains(android.Manifest.permission.CAMERA) == true &&
                        p.packageName != context.packageName
                }
                .map { it.packageName }
                .sorted()
        }.getOrDefault(emptyList())
    }
    var pickingTarget by remember { mutableStateOf(false) }
    var licenseFor by remember { mutableStateOf<ModelManager.ModelState?>(null) }

    // r52a: SAF launchers for model backup / restore (owner has no PC).
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onBackup(uri)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onRestore(uri)
        }
    }

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
                                // Round 44: the file being on disk no longer
                                // means detection runs — session creation is
                                // DEV-gated (owner decisions 1+2).
                                if (scrfdActive) "Ready ✓" else "Ready (not active)",
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
            if (isDebug) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        Text(
                            "r52a debug tools (0 MB)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onInstallStubs) { Text("Install stub models") }
                            TextButton(onClick = onRunProbes) { Text("Run model probes") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDumpCrops) { Text("Dump align crops") }
                            TextButton(onClick = { backupLauncher.launch(null) }) { Text("Back up models") }
                            TextButton(onClick = { restoreLauncher.launch(null) }) { Text("Restore models") }
                        }
                        // r53 TRANSPORT v0
                        HorizontalDivider()
                        Text(
                            "Transport v0",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        Text(
                            transportCaps.ifEmpty { "Tap refresh to detect capabilities" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRefreshTransportCaps) { Text("Detect") }
                            TextButton(onClick = onSelfTest) { Text("Self-test") }
                            TextButton(onClick = { pickingTarget = true }) {
                                Text("Target: ${transportTarget ?: "pick"}")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onSetTransportFeed(!transportFeedOn) }) {
                                Text(if (transportFeedOn) "Stop feed" else "Start feed")
                            }
                            TextButton(onClick = onLaunchThrough) { Text("Launch through VD") }
                        }
                    }
                }
            }
        }
    }

    meteredBlocked?.let { id ->
        AlertDialog(
            onDismissRequest = onMeteredDismiss,
            title = { Text("Metered network") },
            text = {
                Text(
                    "Downloading this model uses metered data " +
                        "(id=$id). Continue anyway?",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = onMeteredProceed) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = onMeteredDismiss) { Text("Wait for Wi-Fi") }
            },
        )
    }

    if (pickingTarget) {
        AlertDialog(
            onDismissRequest = { pickingTarget = false },
            title = { Text("Target app (holds CAMERA)") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    items(cameraApps.size) { i ->
                        TextButton(onClick = {
                            onSetTransportTarget(cameraApps[i])
                            pickingTarget = false
                        }) { Text(cameraApps[i], style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingTarget = false }) { Text("Cancel") }
            },
        )
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
