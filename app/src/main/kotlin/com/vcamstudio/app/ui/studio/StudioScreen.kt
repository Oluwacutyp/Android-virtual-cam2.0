package com.vcamstudio.app.ui.studio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcamstudio.app.ui.theme.StudioAccent
import com.vcamstudio.app.ui.theme.StudioBg
import com.vcamstudio.app.ui.theme.StudioBorder
import com.vcamstudio.app.ui.theme.StudioRed
import com.vcamstudio.app.ui.theme.StudioSurface
import com.vcamstudio.engine.output.RecordingController
import com.vcamstudio.engine.render.model.LensFacing
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.SceneDefinition
import com.vcamstudio.engine.render.model.TransitionType
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import com.vcamstudio.engine.render.render.EngineHealth
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * The broadcast-console layout (blueprint §E): stage first, then transition
 * row, scene bin, layers rail, tool dock — plus inspector/diagnostics/settings
 * sheets. Control-dense by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    lifecycleOwner: LifecycleOwner,
    vm: StudioViewModel = hiltViewModel(),
) {
    LaunchedEffect(lifecycleOwner) { vm.attachLifecycleOwner(lifecycleOwner) }
    val platformLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(platformLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            vm.onLifecycleEvent(event)
        }
        platformLifecycle.lifecycle.addObserver(observer)
        onDispose { platformLifecycle.lifecycle.removeObserver(observer) }
    }
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
    val lutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::onLutPicked) }
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.dismissToast()
        }
    }

    // Round-31: share the finished recording by its content Uri directly —
    // MediaStore rows need no FileProvider hop and no private copy.
    LaunchedEffect(state.lastRecording) {
        state.lastRecording?.let { saved ->
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(android.content.Intent.EXTRA_STREAM, saved.uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            shareLauncher.launch(android.content.Intent.createChooser(intent, "Share recording"))
            vm.clearLastRecording()
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val recEnabled = state.health != EngineHealth.UNHEALTHY &&
                            state.scenes.isNotEmpty() && state.activeSceneId.isNotEmpty()
                        RecChip(state.recording, recEnabled, vm::toggleRecording)
                        StatusChip("%.0f fps".format(state.diagnostics.fps), StudioAccent)
                        StatusChip(state.health.name, healthColor(state.health))
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
                onCameraFront = { vm.addCameraLayer(LensFacing.FRONT) },
                onCameraBack = { vm.addCameraLayer(LensFacing.BACK) },
                onImage = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onVideo = {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onText = { textDialogVisible = true },
                onColor = { colorDialogVisible = true },
                onLut = { lutPicker.launch(arrayOf("*/*")) },
                onMic = vm::toggleMic,
                onMix = { vm.setSheet(StudioViewModel.Sheet.MIXER) },
                onDiag = { vm.setSheet(StudioViewModel.Sheet.DIAGNOSTICS) },
                onClips = {
                    vm.refreshRecordings()
                    vm.setSheet(StudioViewModel.Sheet.RECORDINGS)
                },
            )
        }
    }

    when (state.sheet) {
        StudioViewModel.Sheet.INSPECTOR -> InspectorSheet(
            layer = state.selectedLayer,
            cameraControls = state.selectedLayerId?.let { state.cameraControls[it] },
            cameraState = state.cameraState,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
            onUpdateLayer = vm::updateLayer,
            onUpdateText = vm::updateText,
            onUpdateCameraControls = vm::updateCameraControls,
            onRemove = vm::removeLayer,
            onMoveUp = { vm.moveLayer(it, true) },
            onMoveDown = { vm.moveLayer(it, false) },
            lutNames = state.lutNames,
            onSetLut = { name -> state.selectedLayerId?.let { vm.setLayerLut(it, name) } },
        )
        StudioViewModel.Sheet.DIAGNOSTICS -> DiagnosticsSheet(
            diagnostics = state.diagnostics,
            dumpProvider = vm::diagnosticsDump,
            onForceRecovery = vm::forceRecoveryTest,
            rawMode = state.rawMode,
            onPreviewMode = vm::setPreviewMode,
            uvDebugPass = vm.uvDebugPass.collectAsStateWithLifecycle().value,
            onUvDebug = vm::setUvDebugPass,
            vboDrawPass = vm.vboDrawPass.collectAsStateWithLifecycle().value,
            onVboDraw = vm::setVboDrawPass,
            directSurfacePass = vm.directSurfacePass.collectAsStateWithLifecycle().value,
            onDirectSurface = vm::setDirectSurfacePass,
            staticFboContent = vm.staticFboContent.collectAsStateWithLifecycle().value,
            onStaticFboContent = vm::setStaticFboContent,
            bisectLevel = vm.bisectLevel.collectAsStateWithLifecycle().value,
            onBisect = vm::setBisectLevel,
            scrfdStats = vm.scrfdStats.collectAsStateWithLifecycle().value,
            scrfdPhase = vm.scrfdPhase.collectAsStateWithLifecycle().value,
            scrfdNnapi = vm.scrfdNnapi.collectAsStateWithLifecycle().value,
            onScrfdNnapi = vm::setScrfdNnapi,
            scrfdDevSession = vm.scrfdSessionDev.collectAsStateWithLifecycle().value,
            onScrfdDevSession = vm::setScrfdDevSession,
            monitorMic = vm.monitorMicEnabled.collectAsStateWithLifecycle().value,
            onMonitorMic = vm::setMonitorMic,
            monitorMediaRec = vm.monitorMediaRecDev.collectAsStateWithLifecycle().value,
            onMonitorMediaRec = vm::setMonitorMediaRec,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.SETTINGS -> SettingsSheet(
            resolution = state.sceneResolution,
            onResolution = vm::setSceneResolution,
            onOpenModels = { vm.setSheet(StudioViewModel.Sheet.MODELS) },
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.MIXER -> MixerSheet(
            mixer = vm.audioMixer,
            masterGain = state.masterGain,
            limiterEnabled = state.limiterEnabled,
            onMasterGain = vm::setMasterGain,
            onLimiter = vm::setLimiterEnabled,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.RECORDINGS -> RecordingsSheet(
            items = vm.recordings.collectAsStateWithLifecycle().value,
            onPlay = vm::playRecording,
            onShare = vm::shareRecording,
            onRefresh = vm::refreshRecordings,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.MODELS -> ModelsSheet(
            states = vm.modelStates.collectAsStateWithLifecycle().value,
            scrfdActive = vm.scrfdSessionDev.collectAsStateWithLifecycle().value,
            onDownload = vm::downloadModel,
            onDelete = vm::deleteModel,
            licenseSeen = vm::licenseSeen,
            onMarkLicenseSeen = vm::markLicenseSeen,
            onDismiss = { vm.setSheet(StudioViewModel.Sheet.NONE) },
        )
        StudioViewModel.Sheet.NONE -> Unit
    }

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

@Composable
private fun StageArea(state: StudioViewModel.UiState, vm: StudioViewModel, modifier: Modifier = Modifier) {
    val scene = state.activeScene
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Full-bleed stage: the ENGINE letterboxes the scene into whatever
        // size this surface ends up with, so the SurfaceView always has a
        // definite nonzero size (an aspect-ratio + fillMaxSize combo left the
        // size undetermined on some devices).
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (state.rawMode) {
                    // RAW preview (default): CameraX renders straight into a
                    // PreviewView — driver-proof camera visibility. The GL
                    // engine keeps running underneath for diagnostics.
                    AndroidView(
                        factory = { ctx ->
                            androidx.camera.view.PreviewView(ctx).apply {
                                scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                                implementationMode =
                                    androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                            }
                        },
                        update = { vm.attachPreviewView(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // RAW v1 overlay: imported images render as Compose views
                    // on top of the camera (transform fractions honored;
                    // rotation/blend/effects still GL-mode only).
                    state.activeScene?.layers
                        ?.filterIsInstance<LayerDefinition.Image>()
                        ?.forEach { layer ->
                            vm.rawImageBitmap(layer.sourceId)?.let { bmp ->
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val t = layer.transform
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = layer.name,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .offset(
                                                x = maxWidth * (t.centerX - 0.5f),
                                                y = maxHeight * (t.centerY - 0.5f),
                                            )
                                            .fillMaxWidth(t.width.coerceIn(0.05f, 1f))
                                            .aspectRatio(
                                                bmp.width.toFloat() / bmp.height.toFloat(),
                                            ),
                                    )
                                }
                            }
                        }
                    state.activeScene?.layers
                        ?.filterIsInstance<LayerDefinition.Video>()
                        ?.forEach { layer ->
                            AndroidView(
                                factory = { ctx ->
                                    androidx.media3.ui.PlayerView(ctx).apply {
                                        useController = false
                                        resizeMode =
                                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    }
                                },
                                update = { vm.bindVideoOverlay(layer.sourceId, it) },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxWidth(layer.transform.width.coerceIn(0.05f, 1f))
                                    .fillMaxHeight(layer.transform.height.coerceIn(0.05f, 1f)),
                            )
                        }
                    Text(
                        "RAW PREVIEW — switch to GL compositor in Diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8B949E),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            TextureStageView(ctx).apply {
                                onSurfaceReady = { surface, w, h -> vm.attachStage(surface, w, h) }
                                onSurfaceGone = { vm.detachStage() }
                                onTap = { x, y, vw, vh -> vm.tapToFocus(x, y, vw, vh) }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                // Phase 2: SCRFD debug overlay (cyan box + confidence) or the
                // "Model missing" badge. Reads VM flows directly; the render
                // engine is untouched. Round 44 (owner decision 2): hidden
                // entirely unless the DEV session toggle is on.
                if (vm.scrfdSessionDev.collectAsStateWithLifecycle().value) {
                    FaceDebugOverlay(
                        box = vm.faceOverlay.collectAsStateWithLifecycle().value,
                        phase = vm.scrfdPhase.collectAsStateWithLifecycle().value,
                        rawMode = state.rawMode,
                        scene = scene,
                        sceneRes = state.sceneResolution,
                    )
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
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
        // Round-29: per-interaction edit mode. The delete affordance is INVISIBLE
        // in normal state (it read as a stuck delete-mode before); long-press a
        // tab to reveal its delete action, and it auto-exits on any tap, scene
        // switch, or back-press — it cannot persist.
        var editSceneId by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = editSceneId != null) { editSceneId = null }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scenes, key = { it.id }) { scene ->
                val active = scene.id == activeId
                val inEdit = editSceneId == scene.id && scenes.size > 1
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                active -> StudioAccent.copy(alpha = 0.28f)
                                inEdit -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                                else -> StudioSurface
                            }
                        )
                        .border(
                            BorderStroke(
                                if (active) 2.dp else 1.dp,
                                when {
                                    active -> StudioAccent
                                    inEdit -> MaterialTheme.colorScheme.error
                                    else -> StudioBorder
                                },
                            ),
                            RoundedCornerShape(8.dp),
                        )
                        .combinedClickable(
                            onClick = {
                                editSceneId = null // tap-away auto-exits edit mode
                                onSelect(scene.id)
                            },
                            onLongClick = { if (scenes.size > 1) editSceneId = if (inEdit) null else scene.id },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Text(
                                "● ",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioAccent,
                            )
                        }
                        Text(
                            scene.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) StudioAccent else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (inEdit) {
                        Text(
                            "delete",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                editSceneId = null // per-interaction: exits after the tap
                                onDelete(scene.id)
                            },
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
                        Text(
                            "▲",
                            modifier = Modifier
                                .clickable { onMove(layer.id, true) }
                                .padding(4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "▼",
                            modifier = Modifier
                                .clickable { onMove(layer.id, false) }
                                .padding(4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
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

@Composable
private fun DockBar(
    onCameraFront: () -> Unit,
    onCameraBack: () -> Unit,
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onText: () -> Unit,
    onColor: () -> Unit,
    onLut: () -> Unit,
    onMic: () -> Unit,
    onMix: () -> Unit,
    onDiag: () -> Unit,
    onClips: () -> Unit,
) {
    Surface(color = StudioSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton("📷", "Front", onCameraFront)
            DockButton("🎥", "Back", onCameraBack)
            DockButton("🖼", "Image", onImage)
            DockButton("🎬", "Video", onVideo)
            DockButton("🅣", "Text", onText)
            DockButton("🎨", "Color", onColor)
            DockButton("🌈", "LUT", onLut)
            DockButton("🎚", "Mix", onMix)
            DockButton("🎙", "Mic", onMic)
            DockButton("📊", "Diag", onDiag)
            DockButton("📼", "Clips", onClips)
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

@Composable
private fun TextInputDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it })
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

@Composable
private fun RecChip(state: RecordingController.State, enabled: Boolean, onToggle: () -> Unit) {
    // Round-29: the idle chip used StudioBorder grey and READ as disabled
    // (device screenshot) even though it was clickable. Idle now renders in
    // the active accent so it is obviously tappable; it is only disabled
    // when the engine is unhealthy or no scene is loaded (per the round-29
    // mandate: tappable whenever the engine is healthy and a scene exists).
    val alpha = if (enabled) 1f else 0.4f
    when (state) {
        is RecordingController.State.Recording -> {
            var elapsed by remember(state.startedAtMs) {
                mutableStateOf(((System.currentTimeMillis() - state.startedAtMs) / 1000).toInt())
            }
            LaunchedEffect(state.startedAtMs) {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    elapsed++
                }
            }
            StatusChip(
                text = "● REC %02d:%02d".format(elapsed / 60, elapsed % 60),
                color = Color(0xFFF59E0B),
                modifier = Modifier
                    .alpha(alpha)
                    .clickable(onClick = onToggle),
            )
        }
        else -> StatusChip(
            text = "○ REC",
            color = StudioRed,
            modifier = Modifier
                .alpha(alpha)
                .clickable(enabled = enabled, onClick = onToggle),
        )
    }
}
