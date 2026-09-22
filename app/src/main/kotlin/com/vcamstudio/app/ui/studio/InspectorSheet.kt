package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vcamstudio.app.ui.theme.StudioRed
import com.vcamstudio.engine.capture.CameraSource
import com.vcamstudio.engine.capture.ProControls
import com.vcamstudio.engine.capture.WbMode
import com.vcamstudio.engine.capture.FocusMode
import com.vcamstudio.engine.render.model.FitMode
import com.vcamstudio.engine.render.model.BlendMode
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.TextSpec

/**
 * Per-layer inspector (blueprint §E): transform, blend, effects, and
 * type-specific sections (camera pro controls, text, video playback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorSheet(
    layer: LayerDefinition?,
    cameraControls: ProControls?,
    cameraState: CameraSource.State,
    onDismiss: () -> Unit,
    onUpdateLayer: (String, (LayerDefinition) -> LayerDefinition) -> Unit,
    onUpdateText: (String, TextSpec) -> Unit,
    onUpdateCameraControls: (String, (ProControls) -> ProControls) -> Unit,
    onRemove: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
) {
    if (layer == null) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(layer.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { onMoveUp(layer.id) }) { Text("▲") }
                        TextButton(onClick = { onMoveDown(layer.id) }) { Text("▼") }
                        TextButton(onClick = { onRemove(layer.id) }) {
                            Text("Remove", color = StudioRed)
                        }
                    }
                }
            }

            // ------------------------------------------------ transform
            item { SectionTitle("Transform") }
            item {
                val t = layer.transform
                Column {
                    LabeledSlider("Center X", t.centerX, 0f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(centerX = v) } }
                    }
                    LabeledSlider("Center Y", t.centerY, 0f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(centerY = v) } }
                    }
                    LabeledSlider("Width", t.width, 0.05f..2f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(width = v) } }
                    }
                    LabeledSlider("Height", t.height, 0.05f..2f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(height = v) } }
                    }
                    LabeledSlider("Rotation", t.rotationDeg, -180f..180f, valueText = "${t.rotationDeg.toInt()}°") { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(rotationDeg = v) } }
                    }
                    LabeledSlider("Corner radius", t.cornerRadius, 0f..0.5f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(cornerRadius = v) } }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = t.mirrorX,
                            onClick = { onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(mirrorX = !it.mirrorX) } } },
                            label = { Text("Mirror X") },
                        )
                        FilterChip(
                            selected = t.mirrorY,
                            onClick = { onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(mirrorY = !it.mirrorY) } } },
                            label = { Text("Mirror Y") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FitMode.entries.forEach { mode ->
                            FilterChip(
                                selected = t.fitMode == mode,
                                onClick = { onUpdateLayer(layer.id) { l -> l.withTransform { it.copy(fitMode = mode) } } },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------- opacity/blend
            item { SectionTitle("Compositing") }
            item {
                Column {
                    LabeledSlider("Opacity", layer.opacity, 0f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withOpacity(v) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Blend mode", style = MaterialTheme.typography.bodySmall)
                        var expanded by remember { mutableStateOf(false) }
                        androidx.compose.material3.ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = layer.blendMode.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                                onValueChange = {},
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .androidMenuAnchor()
                                    .padding(0.dp),
                            )
                            androidx.compose.material3.ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                BlendMode.entries.forEach { mode ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                                        onClick = {
                                            onUpdateLayer(layer.id) { l -> l.withBlend(mode) }
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --------------------------------------------------- effects
            item { SectionTitle("Effects") }
            item {
                val fx = layer.effects
                val g = fx.colorGrade
                Column {
                    LabeledSlider("Brightness", g.brightness, -1f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(brightness = v) } }
                    }
                    LabeledSlider("Contrast", g.contrast, 0f..2f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(contrast = v) } }
                    }
                    LabeledSlider("Saturation", g.saturation, 0f..3f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(saturation = v) } }
                    }
                    LabeledSlider("Gamma", g.gamma, 0.2f..4f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(gamma = v) } }
                    }
                    LabeledSlider("Temperature", g.temperature, -1f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(temperature = v) } }
                    }
                    LabeledSlider("Tint", g.tint, -1f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withGrade { it.copy(tint = v) } }
                    }
                    LabeledSlider("Sharpen", fx.sharpen.amount, 0f..1.5f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withSharpen(v) }
                    }
                    LabeledSlider("Blur", fx.blur.radius, 0f..64f, valueText = "%.0f px".format(fx.blur.radius)) { v ->
                        onUpdateLayer(layer.id) { l -> l.withBlur(v) }
                    }
                    LabeledSlider("Vignette", fx.vignette.strength, 0f..1f) { v ->
                        onUpdateLayer(layer.id) { l -> l.withVignette(v) }
                    }
                }
            }

            // ------------------------------------------ type-specific
            when (layer) {
                is LayerDefinition.Camera -> {
                    item { SectionTitle("Camera") }
                    item {
                        CameraSection(layer, cameraControls, cameraState, onUpdateCameraControls)
                    }
                }
                is LayerDefinition.Text -> {
                    item { SectionTitle("Text") }
                    item { TextSection(layer, onUpdateText) }
                }
                is LayerDefinition.Color -> {
                    item { SectionTitle("Fill") }
                    item {
                        Text(
                            "Change color from the 🎨 dock (re-add), adjust opacity above.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                else -> Unit
            }
            item { SpacerUnused() }
        }
    }
}

@Composable
private fun CameraSection(
    layer: LayerDefinition.Camera,
    controls: ProControls?,
    state: CameraSource.State,
    onUpdate: (String, (ProControls) -> ProControls) -> Unit,
) {
    val c = controls ?: return
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = c.lensFacing == com.vcamstudio.engine.capture.LensFacing.FRONT,
                onClick = { onUpdate(layer.id) { it.copy(lensFacing = com.vcamstudio.engine.capture.LensFacing.FRONT) } },
                label = { Text("Front") },
            )
            FilterChip(
                selected = c.lensFacing == com.vcamstudio.engine.capture.LensFacing.BACK,
                onClick = { onUpdate(layer.id) { it.copy(lensFacing = com.vcamstudio.engine.capture.LensFacing.BACK) } },
                label = { Text("Back") },
            )
        }
        LabeledSlider("Zoom", c.zoomRatio, 1f..10f, valueText = "%.1f×".format(c.zoomRatio)) { v ->
            onUpdate(layer.id) { it.copy(zoomRatio = v) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Torch", style = MaterialTheme.typography.bodySmall)
            Switch(checked = c.torch, onCheckedChange = { v -> onUpdate(layer.id) { it.copy(torch = v) } })
        }
        Text(
            when (val s = state) {
                is CameraSource.State.Bound -> "Bound (EV steps ${s.exposureIndexRange ?: "?"})"
                is CameraSource.State.Failed -> "Camera error: ${s.message}"
                CameraSource.State.Starting -> "Starting…"
                CameraSource.State.Idle -> "Idle — add to scene"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle("White balance")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(WbMode.AUTO, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.FLUORESCENT, WbMode.INCANDESCENT).forEach { wb ->
                FilterChip(
                    selected = c.whiteBalance == wb,
                    onClick = { onUpdate(layer.id) { it.copy(whiteBalance = wb) } },
                    label = { Text(wb.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }) },
                )
            }
        }
        SectionTitle("Focus program")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusMode.entries.forEach { fm ->
                FilterChip(
                    selected = c.focusMode == fm,
                    onClick = { onUpdate(layer.id) { it.copy(focusMode = fm) } },
                    label = { Text(fm.name.lowercase()) },
                )
            }
        }
        Text(
            "Tap the stage to focus/meter. ISO & fixed-FPS land with the Phase 1 pro-camera increment.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TextSection(layer: LayerDefinition.Text, onUpdateText: (String, TextSpec) -> Unit) {
    var text by remember(layer.id) { mutableStateOf(layer.spec.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onUpdateText(layer.id, layer.spec.copy(text = it))
        },
        label = { Text("Content") },
        modifier = Modifier.fillMaxWidth(),
    )
    LabeledSlider(
        "Size",
        layer.spec.sizeFraction,
        0.02f..0.2f,
        valueText = "%.0f%%".format(layer.spec.sizeFraction * 100),
    ) { v ->
        onUpdateText(layer.id, layer.spec.copy(sizeFraction = v))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bold", style = MaterialTheme.typography.bodySmall)
        Switch(
            checked = layer.spec.bold,
            onCheckedChange = { v -> onUpdateText(layer.id, layer.spec.copy(bold = v)) },
        )
        TextButton(onClick = { onUpdateText(layer.id, layer.spec.copy(backgroundArgb = if (layer.spec.backgroundArgb != 0L) 0L else 0xAA000000L)) }) {
            Text(if (layer.spec.backgroundArgb != 0L) "Hide box" else "Show box")
        }
    }
}

@Composable
private fun SpacerUnused() {
    androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 32.dp))
}

// Layer helper extensions (map sealed layer -> mutated copy)
private fun LayerDefinition.withTransform(mutate: (com.vcamstudio.engine.render.model.LayerTransform) -> com.vcamstudio.engine.render.model.LayerTransform): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(transform = mutate(transform))
    is LayerDefinition.Image -> copy(transform = mutate(transform))
    is LayerDefinition.Video -> copy(transform = mutate(transform))
    is LayerDefinition.Text -> copy(transform = mutate(transform))
    is LayerDefinition.Color -> copy(transform = mutate(transform))
}

private fun LayerDefinition.withOpacity(value: Float): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(opacity = value)
    is LayerDefinition.Image -> copy(opacity = value)
    is LayerDefinition.Video -> copy(opacity = value)
    is LayerDefinition.Text -> copy(opacity = value)
    is LayerDefinition.Color -> copy(opacity = value)
}

private fun LayerDefinition.withBlend(mode: BlendMode): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(blendMode = mode)
    is LayerDefinition.Image -> copy(blendMode = mode)
    is LayerDefinition.Video -> copy(blendMode = mode)
    is LayerDefinition.Text -> copy(blendMode = mode)
    is LayerDefinition.Color -> copy(blendMode = mode)
}

private fun LayerDefinition.withGrade(mutate: (com.vcamstudio.engine.render.model.ColorGrade) -> com.vcamstudio.engine.render.model.ColorGrade): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(effects = effects.copy(colorGrade = mutate(effects.colorGrade)))
    is LayerDefinition.Image -> copy(effects = effects.copy(colorGrade = mutate(effects.colorGrade)))
    is LayerDefinition.Video -> copy(effects = effects.copy(colorGrade = mutate(effects.colorGrade)))
    is LayerDefinition.Text -> copy(effects = effects.copy(colorGrade = mutate(effects.colorGrade)))
    is LayerDefinition.Color -> copy(effects = effects.copy(colorGrade = mutate(effects.colorGrade)))
}

private fun LayerDefinition.withSharpen(amount: Float): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(effects = effects.copy(sharpen = effects.sharpen.copy(amount = amount)))
    is LayerDefinition.Image -> copy(effects = effects.copy(sharpen = effects.sharpen.copy(amount = amount)))
    is LayerDefinition.Video -> copy(effects = effects.copy(sharpen = effects.sharpen.copy(amount = amount)))
    is LayerDefinition.Text -> copy(effects = effects.copy(sharpen = effects.sharpen.copy(amount = amount)))
    is LayerDefinition.Color -> copy(effects = effects.copy(sharpen = effects.sharpen.copy(amount = amount)))
}

private fun LayerDefinition.withBlur(radius: Float): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(effects = effects.copy(blur = effects.blur.copy(radius = radius)))
    is LayerDefinition.Image -> copy(effects = effects.copy(blur = effects.blur.copy(radius = radius)))
    is LayerDefinition.Video -> copy(effects = effects.copy(blur = effects.blur.copy(radius = radius)))
    is LayerDefinition.Text -> copy(effects = effects.copy(blur = effects.blur.copy(radius = radius)))
    is LayerDefinition.Color -> copy(effects = effects.copy(blur = effects.blur.copy(radius = radius)))
}

private fun LayerDefinition.withVignette(strength: Float): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(effects = effects.copy(vignette = effects.vignette.copy(strength = strength)))
    is LayerDefinition.Image -> copy(effects = effects.copy(vignette = effects.vignette.copy(strength = strength)))
    is LayerDefinition.Video -> copy(effects = effects.copy(vignette = effects.vignette.copy(strength = strength)))
    is LayerDefinition.Text -> copy(effects = effects.copy(vignette = effects.vignette.copy(strength = strength)))
    is LayerDefinition.Color -> copy(effects = effects.copy(vignette = effects.vignette.copy(strength = strength)))
}
