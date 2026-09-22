package com.vcamstudio.app.ui.studio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcamstudio.app.ui.theme.StudioAccent
import com.vcamstudio.app.ui.theme.StudioBg
import com.vcamstudio.app.ui.theme.StudioBorder
import com.vcamstudio.app.ui.theme.StudioSurface
import com.vcamstudio.app.ui.theme.healthColor
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.SceneDefinition
import com.vcamstudio.engine.render.model.TransitionType

/**
 * The broadcast-console layout (blueprint §E): stage first, then transition
 * row, scene bin, layers rail, tool dock — plus inspector/diagnostics/settings
 * sheets. Control-dense by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    lifecycleOwner: LifecycleOwner,
    vm: StudioViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    LaunchedEffect(lifecycleOwner) { vm.attachLifecycleOwner(lifecycleOwner) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var textDialogVisible by remember { mutableStateOf(false) }
    var colorDialogVisible by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::onImagePicked) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::onVideoPicked) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.dismissToast()
        }
    }

    Scaffold(
        containerColor = StudioBg,
        topBar = {
            Surface(color = StudioSurface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("VCam Studio", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            text = "%.0f fps".format(state.diagnostics.fps),
                            color = StudioAccent,
                        )
                        StatusChip(
                            text = state.health.name,
                            color = healthColor(state.health),
                        )
                        Text(
                            "⚙",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .clickable { vm.setSheet(StudioViewModel.Sheet.SETTINGS) }
                                .padding(6.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            StageArea(state, vm, Modifier.weight(1f))
            TransitionRow(state.transitionType, state.fadeDurationMs, vm::setTransitionType, vm::setFadeDuration)
            ScenesStrip(
                state.scenes, state.activeSceneId,
                onSelect = vm::selectScene,
                onAdd = vm::addScene,
                onDelete = vm::deleteScene,
            )
            LayersRail(
                scene = state.activeScene,
                selectedLayerId = state.selectedLayerId,
                micLevel = state.micLevel,
                micRunning = state.micRunning,
                onSelect = vm::selectLayer,
                onMove = vm::moveLayer,
            )
            DockBar(
                onCamera = { vm.addCameraLayer(com.vcamstudio.engine.render.model.LensFacing.FRONT) },
                onCameraBack = { vm.addCameraLayer(com.vcamstudio.engine.render.model.LensFacing.BACK) },
                onImage = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onVideo = {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onText = { vm.setSheet(StudioViewModel.Sheet.NONE); textDialogVisible = true },
                onColor = { colorDialogVisible = true },
                onMic = vm::toggleMic,
                onDiag = { vm.setSheet(StudioViewModel.Sheet.DIAGNOSTICS) },
            )
        }
    }

    // ---- sheets
    when (state.sheet) {
        StudioViewModel.Sheet.INSPECTOR -> InspectorSheet(
            layer = state.selectedLayer,
            cameraControls = state.selectedLayerId?.let { state.cameraControls[it] },
            cameraState = state.cameraState,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
            onUpdateLayer = vm::updateLayer,
            onUpdateText = vm::updateText,
            onUpdateCameraControls = { id, mutate -> vm.updateCameraControls(id, mutate) },
            onRemove = vm::removeLayer,
            onMoveUp = { vm.moveLayer(it, true) },
            onMoveDown = { vm.moveLayer(it, false) },
        )
        StudioViewModel.Sheet.DIAGNOSTICS -> DiagnosticsSheet(
            diagnostics = state.diagnostics,
            dumpProvider = vm::diagnosticsDump,
            onForceRecovery = vm::forceRecoveryTest,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.SETTINGS -> SettingsSheet(
            resolution = state.sceneResolution,
            onResolution = vm::setSceneResolution,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.NONE -> Unit
    }

    // ---- dialogs (local UI state)
    var textDialogVisible by remember { mutableStateOf(false) }
    var colorDialogVisible by remember { mutableStateOf(false) }
    if (textDialogVisible) {
        TextInputDialog(
            title = "Add text layer",
            onConfirm = { vm.addTextLayer(it); textDialogVisible = false },
            onDismiss = { textDialogVisible = false },
        )
    }
    if (colorDialogVisible) {
        ColorPickDialog(
            onPick = { vm.addColorLayer(it); colorDialogVisible = false },
            onDismiss = { colorDialogVisible = false },
        )
    }
}

// ------------------------------------------------------------------- stage

@Composable
private fun StageArea(state: StudioViewModel.UiState, vm: StudioViewModel, modifier: Modifier = Modifier) {
    val scene = state.activeScene
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .aspectRatio(
                    scene?.let { it.width.toFloat() / it.height.toFloat() } ?: 9f / 16f,
                )
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, StudioBorder), RoundedCornerShape(12.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    StageView(ctx).apply {
                        onSurfaceReady = { surface, w, h -> vm.attachStage(surface, w, h) }
                        onSurfaceGone = { vm.detachStage() }
                        onTap = { x, y, vw, vh -> vm.tapToFocus(x, y, vw, vh) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val hasCamera = scene?.layers?.any { it is LayerDefinition.Camera } == true
            if (!hasCamera) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap 📷 in the dock to add a camera layer",
                        color = Color(0xFF8B949E),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // Resolution chip overlay
        StatusChip(
            text = scene?.let { "${it.width}×${it.height}" } ?: "—",
            color = StudioAccent,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
    }
}

@Composable
private fun TransitionRow(
    type: TransitionType,
    fadeMs: Long,
    onType: (TransitionType) -> Unit,
    onFadeMs: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Transition", style = MaterialTheme.typography.labelMedium)
        FilterChip(selected = type == TransitionType.CUT, onClick = { onType(TransitionType.CUT) }, label = { Text("Cut") })
        FilterChip(selected = type == TransitionType.FADE, onClick = { onType(TransitionType.FADE) }, label = { Text("Fade") })
        if (type == TransitionType.FADE) {
            Slider(
                value = fadeMs.toFloat(),
                onValueChange = { onFadeMs(it.toLong()) },
                valueRange = 100f..1500f,
                modifier = Modifier.weight(1f),
            )
            Text("${fadeMs}ms", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ------------------------------------------------------------------ scenes

@Composable
private fun ScenesStrip(
    scenes: List<SceneDefinition>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text("SCENES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scenes, key = { it.id }) { scene ->
                val active = scene.id == activeId
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else StudioSurface)
                        .border(
                            BorderStroke(1.dp, if (active) StudioAccent else StudioBorder),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelect(scene.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(scene.name, style = MaterialTheme.typography.bodySmall)
                    if (scenes.size > 1) {
                        Text(
                            "delete",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { onDelete(scene.id) },
                        )
                    }
                }
            }
            item {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioSurface)
                        .border(BorderStroke(1.dp, StudioBorder), RoundedCornerShape(8.dp))
                        .clickable { onAdd() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ layers

@Composable
private fun LayersRail(
    scene: SceneDefinition?,
    selectedLayerId: String?,
    micLevel: Float,
    micRunning: Boolean,
    onSelect: (String?) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LAYERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (micRunning) {
                Box(
                    Modifier
                        .width(80.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(micLevel.coerceIn(0.01f, 1f))
                            .height(8.dp)
                            .background(StudioAccent),
                    )
                }
            }
        }
        val layers = scene?.layers?.asReversed() ?: emptyList()
        layers.take(4).forEach { layer ->
            val selected = layer.id == selectedLayerId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(layer.id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (layer.visible) "👁 " else "— ") + layerName(layer),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (layer.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected) {
                    Row {
                        Text("▲", modifier = Modifier
                            .clickable { onMove(layer.id, true) }
                            .padding(4.dp), style = MaterialTheme.typography.labelSmall)
                        Text("▼", modifier = Modifier
                            .clickable { onMove(layer.id, false) }
                            .padding(4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (layers.size > 4) {
            Text(
                "+${layers.size - 4} more — select a layer to inspect",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun layerName(layer: LayerDefinition): String = when (layer) {
    is LayerDefinition.Camera -> layer.name
    is LayerDefinition.Image -> layer.name
    is LayerDefinition.Video -> layer.name
    is LayerDefinition.Text -> layer.spec.text.take(18)
    is LayerDefinition.Color -> layer.name
}

// -------------------------------------------------------------------- dock

@Composable
private fun DockBar(
    onCamera: () -> Unit,
    onCameraBack: () -> Unit,
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onText: () -> Unit,
    onColor: () -> Unit,
    onMic: () -> Unit,
    onDiag: () -> Unit,
) {
    Surface(color = StudioSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton("📷", "Front") { onCamera() }
            DockButton("🎥", "Back") { onCameraBack() }
            DockButton("🖼", "Image") { onImage() }
            DockButton("🎬", "Video") { onVideo() }
            DockButton("🅣", "Text") { onText() }
            DockButton("🎨", "Color") { onColor() }
            DockButton("🎙", "Mic") { onMic() }
            DockButton("📊", "Diag") { onDiag() }
        }
    }
}

@Composable
private fun DockButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------- dialogs

@Composable
private fun TextInputDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val PRESET_COLORS = intArrayOf(
    0xFF22D3EE.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt(),
    0xFF8B5CF6.toInt(), 0xFF0EA5E9.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
)

@Composable
private fun ColorPickDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add color layer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick a fill color", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_COLORS.take(4).forEach { c ->
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(c))
                                .clickable { onPick(c) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_COLORS.drop(4).forEach { c ->
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(c))
                                .clickable { onPick(c) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
