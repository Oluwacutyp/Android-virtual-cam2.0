package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vcamstudio.app.recording.RecordingStore

/**
 * Round-31 (owner mandate 2): the in-app recordings library — backed by the
 * SAME MediaStore rows the system gallery shows (Movies/VCamStudio, vcam_*).
 * Tap = play via the system player on the content Uri; Share = ACTION_SEND on
 * the same Uri (no FileProvider hop, no private copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsSheet(
    items: List<RecordingStore.Item>,
    onPlay: (RecordingStore.Item) -> Unit,
    onShare: (RecordingStore.Item) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Recordings", style = MaterialTheme.typography.titleMedium)
            Text(
                "Movies/VCamStudio — also in your gallery & Files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Text(
                    "No recordings yet — tap ● REC. Clips land here and in your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                items(items.size) { i ->
                    val item = items[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(item) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.1f MB".format(item.sizeBytes / 1_000_000f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onShare(item) }) { Text("Share") }
                    }
                    if (i < items.size - 1) HorizontalDivider()
                }
            }
        }
    }
}
