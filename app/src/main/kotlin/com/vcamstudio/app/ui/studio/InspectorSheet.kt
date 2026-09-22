package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vcamstudio.engine.capture.FocusMode
import com.vcamstudio.engine.capture.ProControls
import com.vcamstudio.engine.capture.WbMode
import com.vcamstudio.engine.render.model.BlendMode
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.TextAlignment
import com.vcamstudio.engine.render.model.TextSpec

/**
 * Per-layer control sheet: transform, compositing, effects, plus type-specific
 * sections (camera pro controls / text styling). Control-dense by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorSheet(
    layer: LayerDefinition?,
    cameraControls: ProControls?,
    cameraState: com.vcamstudio.engine.capture.CameraSource.State,
    onDismiss: () -> Unit,
    onUpdateLayer: (String, (LayerDefinition) -> LayerDefinition) -> Unit,
    onUpdateText: (String, TextSpec) -> Unit,
    onUpdateCameraControls: (String, (ProControls) -> ProControls) -> Unit,
    onRemove: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
) {
    if (layer == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(layer.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onMoveUp(layer.id) }) { Text("▲") }
                TextButton(onClick = { onMoveDown(layer.id) }) { Text("▼") }
                Button(onClick = { onRemove(layer.id) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }

        TransformSection(layer, onUpdateLayer)
        CompositingSection(layer, onUpdateLayer)
        EffectsSection(layer, onUpdateLayer)

        when (layer) {
            is LayerDefinition.Camera -> if (cameraControls != null) {
                CameraProSection(layer.id, cameraControls, cameraState, onUpdateCameraControls)
            }
            is LayerDefinition.Text -> TextSection(layer.id, layer.spec, onUpdateText)
            else -> Unit
        }
    }
}

@Composable
private fun TransformSection(layer: LayerDefinition, onUpdateLayer: (String, (LayerDefinition) -> LayerDefinition) -> Unit) {
    val t = layer.transform
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Transform")
        LabeledSlider("Center X", t.centerX, 0f..1f, valueText = "%.2f".format(t.centerX)) { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(centerX = v) } }
        }
        LabeledSlider("Center Y", t.centerY, 0f..1f, valueText = "%.2f".format(t.centerY)) { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(centerY = v) } }
        }
        LabeledSlider("Width", t.width, 0.05f..1f, valueText = "%.0f%%".format(t.width * 100)) { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(width = v) } }
        }
        LabeledSlider("Height", t.height, 0.05f..1f, valueText = "%.0f%%".format(t.height * 100)) { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(height = v) } }
        }
        LabeledSlider("Rotation", t.rotationDeg, -180f..180f, valueText = "${t.rotationDeg.toInt()}°") { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(rotationDeg = v) } }
        }
        LabeledSlider("Corner radius", t.cornerRadius, 0f..0.5f, valueText = "%.2f".format(t.cornerRadius)) { v ->
            onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(cornerRadius = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = t.mirrorX,
                onClick = { onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(mirrorX = !it.mirrorX) } } },
                label = { Text("Mirror X") },
            )
            FilterChip(
                selected = t.mirrorY,
                onClick = { onUpdateLayer(layer.id) { def -> withTransform(def) { it.copy(mirrorY = !it.mirrorY) } } },
                label = { Text("Mirror Y") },
            )
            FilterChip(
                selected = layer.visible,
                onClick = { onUpdateLayer(layer.id) { def -> setVisible(def, !def.visible) } },
                label = { Text(if (layer.visible) "Visible" else "Hidden") },
            )
        }
    }
}

@Composable
private fun CompositingSection(layer: LayerDefinition, onUpdateLayer: (String, (LayerDefinition) -> LayerDefinition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Compositing")
        LabeledSlider("Opacity", layer.opacity, 0f..1f, valueText = "%.0f%%".format(layer.opacity * 100)) { v ->
            onUpdateLayer(layer.id) { def -> withOpacity(def, v) }
        }
        Dropdown(
            label = "Blend mode",
            options = BlendMode.entries.toList(),
            selected = layer.blendMode,
            display = { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { mode ->
                onUpdateLayer(layer.id) { def -> withBlend(def, mode) }
            },
        )
    }
}

@Composable
private fun EffectsSection(layer: LayerDefinition, onUpdateLayer: (String, (LayerDefinition) -> LayerDefinition) -> Unit) {
    val fx = layer.effects
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Effects")
        LabeledSlider("Brightness", fx.colorGrade.brightness, -0.5f..0.5f, valueText = "%.2f".format(fx.colorGrade.brightness)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(brightness = v)) } }
        }
        LabeledSlider("Contrast", fx.colorGrade.contrast, 0.2f..3f, valueText = "%.2f".format(fx.colorGrade.contrast)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(contrast = v)) } }
        }
        LabeledSlider("Saturation", fx.colorGrade.saturation, 0f..3f, valueText = "%.2f".format(fx.colorGrade.saturation)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(saturation = v)) } }
        }
        LabeledSlider("Gamma", fx.colorGrade.gamma, 0.2f..3f, valueText = "%.2f".format(fx.colorGrade.gamma)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(gamma = v)) } }
        }
        LabeledSlider("Temperature", fx.colorGrade.temperature, -1f..1f, valueText = "%.2f".format(fx.colorGrade.temperature)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(temperature = v)) } }
        }
        LabeledSlider("Tint", fx.colorGrade.tint, -1f..1f, valueText = "%.2f".format(fx.colorGrade.tint)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(colorGrade = e.colorGrade.copy(tint = v)) } }
        }
        LabeledSlider("Blur", fx.blur.radius, 0f..64f, valueText = "%.0f px".format(fx.blur.radius)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(blur = e.blur.copy(radius = v)) } }
        }
        LabeledSlider("Sharpen", fx.sharpen.amount, 0f..2f, valueText = "%.2f".format(fx.sharpen.amount)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(sharpen = e.sharpen.copy(amount = v)) } }
        }
        LabeledSlider("Vignette", fx.vignette.strength, 0f..1f, valueText = "%.2f".format(fx.vignette.strength)) { v ->
            onUpdateLayer(layer.id) { def -> withEffects(def) { e -> e.copy(vignette = e.vignette.copy(strength = v)) } }
        }
    }
}

@Composable
private fun CameraProSection(
    layerId: String,
    controls: ProControls,
    cameraState: com.vcamstudio.engine.capture.CameraSource.State,
    onUpdate: (String, (ProControls) -> ProControls) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Camera Pro")
        val bound = cameraState as? com.vcamstudio.engine.capture.CameraSource.State.Bound
        Text(
            when (cameraState) {
                is com.vcamstudio.engine.capture.CameraSource.State.Bound ->
                    "Bound · ${cameraState.lensFacing}"
                com.vcamstudio.engine.capture.CameraSource.State.Starting -> "Starting…"
                com.vcamstudio.engine.capture.CameraSource.State.Idle -> "Idle"
                is com.vcamstudio.engine.capture.CameraSource.State.Failed -> "Failed: ${cameraState.message}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Dropdown(
            label = "White balance",
            options = WbMode.entries.toList(),
            selected = controls.whiteBalance,
            display = { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { mode -> onUpdate(layerId) { it.copy(whiteBalance = mode) } },
        )
        Dropdown(
            label = "Focus mode",
            options = FocusMode.entries.toList(),
            selected = controls.focusMode,
            display = { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { mode -> onUpdate(layerId) { it.copy(focusMode = mode) } },
        )

        val evRange = bound?.exposureIndexRange
        if (evRange != null && !evRange.isEmpty() && evRange.last > evRange.first) {
            val current = (controls.exposureCompensationIndex ?: 0).coerceIn(evRange.first, evRange.last)
            LabeledSlider(
                "Exposure comp",
                current.toFloat(),
                evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first - 1).coerceAtLeast(0),
                valueText = "%+d".format(current),
            ) { v ->
                val idx = v.toInt()
                onUpdate(layerId) { it.copy(exposureCompensationIndex = idx) }
            }
        }
        LabeledSlider("Zoom", controls.zoomRatio, 1f..8f, valueText = "%.1fx".format(controls.zoomRatio)) { v ->
            onUpdate(layerId) { it.copy(zoomRatio = v) }
        }
        if (bound?.hasFlash == true) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Torch")
                Switch(checked = controls.torch, onCheckedChange = { on ->
                    onUpdate(layerId) { it.copy(torch = on) }
                })
            }
        }
    }
}

@Composable
private fun TextSection(layerId: String, spec: TextSpec, onUpdateText: (String, TextSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Text")
        OutlinedTextField(
            value = spec.text,
            onValueChange = { onUpdateText(layerId, spec.copy(text = it)) },
            label = { Text("Content") },
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledSlider("Size", spec.sizeFraction, 0.02f..0.3f, valueText = "%.0f%%".format(spec.sizeFraction * 100)) { v ->
            onUpdateText(layerId, spec.copy(sizeFraction = v))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAlignment.entries.forEach { alignment ->
                FilterChip(
                    selected = spec.alignment == alignment,
                    onClick = { onUpdateText(layerId, spec.copy(alignment = alignment)) },
                    label = { Text(alignment.name.take(3)) },
                )
            }
            FilterChip(
                selected = spec.bold,
                onClick = { onUpdateText(layerId, spec.copy(bold = !spec.bold)) },
                label = { Text("Bold") },
            )
        }
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ---- LayerDefinition copy helpers (field-level mutation via data-class copy) ----

private inline fun withTransform(def: LayerDefinition, mutate: (com.vcamstudio.engine.render.model.LayerTransform) -> com.vcamstudio.engine.render.model.LayerTransform): LayerDefinition = when (def) {
    is LayerDefinition.Camera -> def.copy(transform = mutate(def.transform))
    is LayerDefinition.Image -> def.copy(transform = mutate(def.transform))
    is LayerDefinition.Video -> def.copy(transform = mutate(def.transform))
    is LayerDefinition.Text -> def.copy(transform = mutate(def.transform))
    is LayerDefinition.Color -> def.copy(transform = mutate(def.transform))
}

private fun withOpacity(def: LayerDefinition, value: Float): LayerDefinition = when (def) {
    is LayerDefinition.Camera -> def.copy(opacity = value)
    is LayerDefinition.Image -> def.copy(opacity = value)
    is LayerDefinition.Video -> def.copy(opacity = value)
    is LayerDefinition.Text -> def.copy(opacity = value)
    is LayerDefinition.Color -> def.copy(opacity = value)
}

private fun withBlend(def: LayerDefinition, mode: BlendMode): LayerDefinition = when (def) {
    is LayerDefinition.Camera -> def.copy(blendMode = mode)
    is LayerDefinition.Image -> def.copy(blendMode = mode)
    is LayerDefinition.Video -> def.copy(blendMode = mode)
    is LayerDefinition.Text -> def.copy(blendMode = mode)
    is LayerDefinition.Color -> def.copy(blendMode = mode)
}

private fun setVisible(def: LayerDefinition, value: Boolean): LayerDefinition = when (def) {
    is LayerDefinition.Camera -> def.copy(visible = value)
    is LayerDefinition.Image -> def.copy(visible = value)
    is LayerDefinition.Video -> def.copy(visible = value)
    is LayerDefinition.Text -> def.copy(visible = value)
    is LayerDefinition.Color -> def.copy(visible = value)
}

private inline fun withEffects(def: LayerDefinition, mutate: (com.vcamstudio.engine.render.model.LayerEffects) -> com.vcamstudio.engine.render.model.LayerEffects): LayerDefinition = when (def) {
    is LayerDefinition.Camera -> def.copy(effects = mutate(def.effects))
    is LayerDefinition.Image -> def.copy(effects = mutate(def.effects))
    is LayerDefinition.Video -> def.copy(effects = mutate(def.effects))
    is LayerDefinition.Text -> def.copy(effects = mutate(def.effects))
    is LayerDefinition.Color -> def.copy(effects = mutate(def.effects))
}
