package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Open-source licenses + attribution (Phase 1 deliverable 9). Everything
 * bundled in the APK is Apache-2.0; non-commercial AI models (later phases)
 * are user-downloaded at runtime and never bundled (blueprint §Model Manager).
 */
@Composable
fun LicensesSheet() {
    val entries = listOf(
        "AndroidX CameraX 1.3.4" to "Apache License 2.0",
        "AndroidX Media3 / ExoPlayer 1.4.1" to "Apache License 2.0",
        "Jetpack Compose + Material 3 (BOM 2024.09.03)" to "Apache License 2.0",
        "Hilt 2.52" to "Apache License 2.0",
        "DataStore 1.1.1" to "Apache License 2.0",
        "Timber 5.0.1" to "Apache License 2.0",
        "Kotlin 2.0.20 / coroutines 1.8.1" to "Apache License 2.0",
    )
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle("Open-source software") }
        items(entries.size) { i ->
            Column {
                Text(entries[i].first, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entries[i].second,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionTitle("Content notes") }
        item {
            Text(
                "This build bundles only Apache-2.0 components. Color LUT files " +
                    "(.cube) you import remain your content and are processed entirely " +
                    "on-device. Nothing is uploaded — the app has no internet permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
