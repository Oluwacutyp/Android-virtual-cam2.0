package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.vcamstudio.app.ui.theme.StudioAccent
import com.vcamstudio.engine.render.render.DiagnosticsSnapshot

/**
 * Live engine vitals: FPS, frame-time histogram, drop counters, recovery log.
 * This screen is the proof behind the "never black" guarantee.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    diagnostics: DiagnosticsSnapshot,
    dumpProvider: () -> String,
    onForceRecovery: () -> Unit,
    rawMode: Boolean,
    onPreviewMode: (Boolean) -> Unit,
    uvDebugPass: Boolean,
    onUvDebug: (Boolean) -> Unit,
    vboDrawPass: Boolean,
    onVboDraw: (Boolean) -> Unit,
    directSurfacePass: Boolean,
    onDirectSurface: (Boolean) -> Unit,
    staticFboContent: Boolean,
    onStaticFboContent: (Boolean) -> Unit,

    bisectLevel: Int,
    onBisect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val isDebugBuild = androidx.compose.ui.platform.LocalContext.current
        .applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(diagnostics.health.name, healthColor(diagnostics.health))
                    StatusChip("%.0f fps".format(diagnostics.fps), StudioAccent)
                    StatusChip("presented ${diagnostics.presentedFrames}", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Preview path", style = MaterialTheme.typography.labelMedium)
                    FilterChip(selected = rawMode, onClick = { onPreviewMode(true) }, label = { Text("RAW (CameraX)") })
                    FilterChip(selected = !rawMode, onClick = { onPreviewMode(false) }, label = { Text("GL compositor") })
                }
            }
            if (isDebugBuild) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "DEV: UV gradient pass (round-16A probe)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        androidx.compose.material3.Switch(
                            checked = uvDebugPass,
                            onCheckedChange = onUvDebug,
                        )
                    }
                }
            }
            if (isDebugBuild) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "DEV: fresh VBO/VAO draw path (round-16D test)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        androidx.compose.material3.Switch(
                            checked = vboDrawPass,
                            onCheckedChange = onVboDraw,
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "DEV: static FBO content (round-23 test)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        androidx.compose.material3.Switch(
                            checked = staticFboContent,
                            onCheckedChange = onStaticFboContent,
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "DEV: render direct to EGL surface (round-17 test)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        androidx.compose.material3.Switch(
                            checked = directSurfacePass,
                            onCheckedChange = onDirectSurface,
                        )
                    }
                }
                item {
                    Text(
                        "BISECTION (round 19): T6 baseline = all off",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = bisectLevel == 1,
                            onClick = { onBisect(if (bisectLevel == 1) 0 else 1) },
                            label = { Text("T1") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = bisectLevel == 2,
                            onClick = { onBisect(if (bisectLevel == 2) 0 else 2) },
                            label = { Text("T2") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = bisectLevel == 3,
                            onClick = { onBisect(if (bisectLevel == 3) 0 else 3) },
                            label = { Text("T3") },
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = bisectLevel == 4,
                            onClick = { onBisect(if (bisectLevel == 4) 0 else 4) },
                            label = { Text("T4") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = bisectLevel == 5,
                            onClick = { onBisect(if (bisectLevel == 5) 0 else 5) },
                            label = { Text("T5") },
                        )
                    }
                }
                item {
                    Text(
                        "T1 solid quad (in-shader verts) · T2 +attrib · T3 +FBO · " +
                            "T4 +OES · T5 full pipeline, plain 0..1 UVs · T6 = all off",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            diagnostics.initError?.let { err ->
                item {
                    Text(
                        "ENGINE INIT FAILED: $err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            item { SectionTitle("Frame times") }
            item {
                Text(
                    "p50 %.1f ms · p95 %.1f ms · max %.1f ms".format(
                        diagnostics.p50Ms, diagnostics.p95Ms, diagnostics.maxMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Histogram(diagnostics.histogram) }
            item { SectionTitle("Pipeline") }
            item {
                Text(
                    buildString {
                        appendLine("dropped frames: ${diagnostics.droppedFrames}")
                        appendLine("last present: ${diagnostics.lastPresentAgeMs} ms ago")
                        appendLine("scene: ${diagnostics.sceneSize?.width}×${diagnostics.sceneSize?.height}")
                        appendLine("preview: ${diagnostics.previewSize?.width}×${diagnostics.previewSize?.height}")
                        appendLine("external sources: ${diagnostics.externalSourceCount}")
                        appendLine("GPU: ${diagnostics.glRenderer}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { SectionTitle("Recovery log (${diagnostics.recoveries.size})") }
            items(diagnostics.recoveries.asReversed().take(8)) { event ->
                Text(
                    "@${event.atMs} — ${event.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onForceRecovery) { Text("Force recovery test") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(dumpProvider())) }) {
                        Text("Copy dump")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Histogram(histogram: List<Int>) {
    val max = (histogram.maxOrNull() ?: 0).coerceAtLeast(1)
    val labels = listOf("8", "17", "25", "33", "50", "67", "100", "100+")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            histogram.forEach { count ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val frac = count.toFloat() / max
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((frac * 64).dp)
                            .background(StudioAccent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .align(androidx.compose.ui.Alignment.BottomCenter),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
