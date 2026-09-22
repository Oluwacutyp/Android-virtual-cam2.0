package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vcamstudio.app.BuildConfig
import com.vcamstudio.app.settings.SceneResolution

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    resolution: SceneResolution,
    onResolution: (SceneResolution) -> Unit,
    onDismiss: () -> Unit,
) {
    var showLicenses by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (showLicenses) {
            LicensesSheet()
            return@ModalBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item { SectionTitle("Scene resolution") }
            items(SceneResolution.entries.size) { index ->
                val entry = SceneResolution.entries[index]
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${entry.label}  (${entry.width}×${entry.height})", style = MaterialTheme.typography.bodyMedium)
                    RadioButton(selected = resolution == entry, onClick = { onResolution(entry) })
                }
            }
            item { SectionTitle("Open-source licenses") }
            item {
                Text(
                    "View licenses & attribution (all bundled components are Apache-2.0)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLicenses = true }
                        .padding(vertical = 8.dp),
                )
            }
            item { SectionTitle("About") }
            item {
                Text(
                    buildString {
                        appendLine("VCam Studio ${BuildConfig.VERSION_NAME} — Phase 1 build.")
                        appendLine()
                        appendLine("100% free · no watermark · no subscription · fully offline.")
                        appendLine("Everything renders on your device; nothing is uploaded.")
                        appendLine()
                        appendLine("Open-source licenses: every bundled component is Apache-2.0 — tap below.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
