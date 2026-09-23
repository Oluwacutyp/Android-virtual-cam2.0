package com.vcamstudio.app.ui.studio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcamstudio.app.settings.SceneResolution
import com.vcamstudio.app.settings.StudioSettings
import com.vcamstudio.core.dispatch.DispatcherProvider
import com.vcamstudio.engine.audio.AudioBusId
import com.vcamstudio.engine.audio.AudioMixer
import com.vcamstudio.engine.audio.MicLevelMonitor
import com.vcamstudio.engine.capture.CameraSource
import com.vcamstudio.engine.capture.LensFacing
import com.vcamstudio.engine.capture.ProControls
import com.vcamstudio.engine.media.ImageLoader
import com.vcamstudio.engine.media.MixerAudioTap
import com.vcamstudio.engine.media.VideoLayerController
import com.vcamstudio.engine.output.RecordingController
import com.vcamstudio.engine.render.lut.CubeLutParser
import com.vcamstudio.engine.render.model.BlendMode
import com.vcamstudio.engine.render.model.ColorGrade
import com.vcamstudio.engine.render.model.LayerDefinition
import com.vcamstudio.engine.render.model.LayerEffects
import com.vcamstudio.engine.render.model.LayerTransform
import com.vcamstudio.engine.render.model.LensFacing as RenderLensFacing
import com.vcamstudio.engine.render.render.DiagnosticsSnapshot
import com.vcamstudio.engine.render.render.EngineHealth
import com.vcamstudio.engine.render.render.RenderEngine
import com.vcamstudio.engine.render.model.SceneDefinition
import com.vcamstudio.engine.render.model.TextSpec
import com.vcamstudio.engine.render.model.TransitionSpec
import com.vcamstudio.engine.render.model.TransitionType
import com.vcamstudio.engine.render.source.TextRasterizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Studio brain: owns scenes/layers, synchronizes them into the render engine,
 * and wires engine-provided external surfaces to CameraX / ExoPlayer.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val engine: RenderEngine,
    private val cameraSource: CameraSource,
    private val mic: MicLevelMonitor,
    private val settings: StudioSettings,
    private val dispatchers: DispatcherProvider,
    private val mixer: AudioMixer,
    private val recorder: RecordingController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    enum class Sheet { NONE, INSPECTOR, DIAGNOSTICS, SETTINGS, MIXER }

    data class VideoParams(
        val uri: Uri,
        val loop: Boolean = true,
        val muted: Boolean = false,
        val volume: Float = 1f,
        val speed: Float = 1f,
    )

    data class UiState(
        val scenes: List<SceneDefinition> = emptyList(),
        val activeSceneId: String = "",
        val selectedLayerId: String? = null,
        val transitionType: TransitionType = TransitionType.CUT,
        val fadeDurationMs: Long = 300L,
        val cameraState: CameraSource.State = CameraSource.State.Idle,
        val cameraControls: Map<String, ProControls> = emptyMap(),
        val health: EngineHealth = EngineHealth.HEALTHY,
        val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
        val micLevel: Float = 0f,
        val micRunning: Boolean = false,
        val sheet: Sheet = Sheet.NONE,
        val sceneResolution: SceneResolution = SceneResolution.P_720,
        val toast: String? = null,
        /** TRUE = CameraX PreviewView direct (default, driver-proof); FALSE = GL compositor. */
        val rawMode: Boolean = true,
        val recording: RecordingController.State = RecordingController.State.Idle,
        val masterGain: Float = 1f,
        val limiterEnabled: Boolean = true,
        val lutNames: List<String> = emptyList(),
        val lastRecordingPath: String? = null,
    ) {
        val activeScene: SceneDefinition?
            get() = scenes.firstOrNull { it.id == activeSceneId }

        val selectedLayer: LayerDefinition?
            get() = activeScene?.layers?.firstOrNull { it.id == selectedLayerId }
    }

    private val scenes = MutableStateFlow<List<SceneDefinition>>(emptyList())
    private val activeSceneId = MutableStateFlow("")
    private val selectedLayerId = MutableStateFlow<String?>(null)
    private val transitionType = MutableStateFlow(TransitionType.CUT)
    private val fadeDurationMs = MutableStateFlow(300L)
    private val sheet = MutableStateFlow(Sheet.NONE)
    private val cameraControls = MutableStateFlow<Map<String, ProControls>>(emptyMap())
    private val toast = MutableStateFlow<String?>(null)
    private val rawMode = MutableStateFlow(true)
    private var previewViewRef: androidx.camera.view.PreviewView? = null
    private val sceneResolution = MutableStateFlow(SceneResolution.P_720)
    private val lutNames = MutableStateFlow<List<String>>(emptyList())
    private val lastRecordingPath = MutableStateFlow<String?>(null)
    private var audioPumpJob: kotlinx.coroutines.Job? = null

    private val videoControllers = LinkedHashMap<String, VideoLayerController>()
    private val videoParams = LinkedHashMap<String, VideoParams>()
    private val imageCache = LinkedHashMap<String, android.graphics.Bitmap>()
    /** Engine-owned surfaces per camera layer id, kept for fast rebinds. */
    private val cameraSurfaces = LinkedHashMap<String, android.view.Surface>()

    private var lifecycleOwner: LifecycleOwner? = null

    val uiState: StateFlow<UiState> = combine(
        combine(scenes, activeSceneId, selectedLayerId, sheet, rawMode) { s, a, sel, sh, raw ->
            Quint(s, a, sel, sh, raw)
        },
        combine(
            cameraSource.state, cameraControls, transitionType, fadeDurationMs, toast,
        ) { cs, cc, tt, fd, msg ->
            Quint(cs, cc, tt, fd, msg)
        },
        combine(
            engine.diagnostics, engine.health, mic.levelRms, mic.running, recorder.state,
        ) { d, h, lvl, run, rec ->
            Quint(d, h, lvl, run, rec)
        },
        combine(
            sceneResolution, lutNames, lastRecordingPath, mixer.masterGain, mixer.limiterEnabled,
        ) { res, luts, lastRec, mg, lim ->
            Quint(res, luts, lastRec, mg, lim)
        },
    ) { core, cameraStuff, diagStuff, extra ->
        UiState(
            scenes = core.a,
            activeSceneId = core.b,
            selectedLayerId = core.c,
            sheet = core.d,
            rawMode = core.e,
            cameraState = cameraStuff.a,
            cameraControls = cameraStuff.b,
            transitionType = cameraStuff.c,
            fadeDurationMs = cameraStuff.d,
            toast = cameraStuff.e,
            diagnostics = diagStuff.a,
            health = diagStuff.b,
            micLevel = diagStuff.c,
            micRunning = diagStuff.d,
            recording = diagStuff.e,
            sceneResolution = extra.a,
            lutNames = extra.b,
            lastRecordingPath = extra.c,
            masterGain = extra.d,
            limiterEnabled = extra.e,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    init {
        // Engine -> app: external surfaces for camera/video sources.
        engine.onExternalSourceReady = { sourceId, surface, _, _ ->
            onExternalSourceReady(sourceId, surface)
        }
        engine.onExternalSourceReleased = { sourceId ->
            onExternalSourceReleased(sourceId)
        }

        // Mic PCM (48 kHz mono) feeds the mixer MIC bus for recording.
        mic.pcmSink = { pcm, _ -> mixer.offerPcm(AudioBusId.MIC, pcm, 1) }

        viewModelScope.launch {
            settings.sceneResolution.collect { sceneResolution.value = it }
        }
        viewModelScope.launch {
            // Camera bound -> apply sensor rotation to camera-layer UVs so the
            // GL compositor shows upright content (RAW/PreviewView does its own).
            cameraSource.state.collect { st ->
                if (st !is CameraSource.State.Bound) return@collect
                // Sampling rotation == the sensor value (device-calibrated
                // round 14: rot 0 -> 90 off, rot 90 -> upside-down, rot 270 ->
                // upright). CameraX Transform-output contract: the BUFFER is
                // NOT pre-rotated ("the output ... is twofold: the buffer and
                // the transformation info"); the renderer counter-rotates the
                // sampling window by the sensor value post-ST (SourceUvMath).
                // Front camera also mirrors (selfie preview convention,
                // applied post-ST so the ST flip can't conjugate it into a
                // vertical flip).
                val rot = cameraSource.rotationDegrees().toFloat()
                val mirror = cameraSource.isFrontCamera()
                var changed = false
                scenes.value = scenes.value.map { s ->
                    s.copy(layers = s.layers.map { l ->
                        if (l is LayerDefinition.Camera &&
                            (l.transform.uvRotationDeg != rot || l.transform.mirrorX != mirror)
                        ) {
                            changed = true
                            l.copy(transform = l.transform.copy(uvRotationDeg = rot, mirrorX = mirror))
                        } else l
                    })
                }
                if (changed) commit()
            }
        }
        viewModelScope.launch {
            // Kick the engine alive with a first scene.
            engine.start()
            createSceneInternal()
        }
    }

    fun attachLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    /**
     * CameraX unbinds when the lifecycle stops (background, task switch,
     * activity recreation). ON_START re-binds every camera layer from the
     * cached engine surfaces so frames resume — otherwise the layer sits
     * frame-dead forever after any lifecycle bounce.
     */
    fun onLifecycleEvent(event: androidx.lifecycle.Lifecycle.Event) {
        if (event != androidx.lifecycle.Lifecycle.Event.ON_START) return
        if (lifecycleOwner == null) return
        if (rawMode.value) {
            Timber.i("LIFECYCLE_REBIND raw")
            maybeBindRawCamera()
        } else {
            rebindCameraToEngine()
        }
    }

    fun attachStage(surface: android.view.Surface, width: Int, height: Int) {
        engine.attachPreview(surface, width, height)
    }

    /** RAW path: the PreviewView instance from the UI (idempotent per instance). */
    fun attachPreviewView(view: androidx.camera.view.PreviewView) {
        if (previewViewRef === view) return
        previewViewRef = view
        maybeBindRawCamera()
    }

    /** RAW preview overlay: decoded bitmap for an image layer's source. */
    fun rawImageBitmap(sourceId: String): android.graphics.Bitmap? = imageCache[sourceId]

    /** RAW overlay: route a video layer's player into the given PlayerView. */
    fun bindVideoOverlay(sourceId: String, view: androidx.media3.ui.PlayerView) {
        val controller = videoControllers[sourceId] ?: return
        if (view.player === controller.player) return // recomposition-safe
        controller.showOnPlayerView(view)
    }

    fun setPreviewMode(raw: Boolean) {
        if (rawMode.value == raw) return
        rawMode.value = raw
        Timber.i("PREVIEW_MODE ${if (raw) "RAW" else "GL"}")
        if (raw) {
            maybeBindRawCamera()
        } else {
            // Video layers return from PlayerView overlays to the engine texture.
            videoControllers.values.forEach { it.showOnPlayerView(null) }
            rebindCameraToEngine()
        }
    }

    private fun maybeBindRawCamera() {
        val view = previewViewRef ?: return
        val owner = lifecycleOwner ?: return
        if (!rawMode.value) return
        val scene = uiState.value.activeScene ?: return
        scene.layers.filterIsInstance<LayerDefinition.Camera>().firstOrNull()?.let { layer ->
            val controls = cameraControls.value[layer.id] ?: ProControls()
            cameraSource.bindPreviewView(view, controls, owner)
        }
    }

    private fun rebindCameraToEngine() {
        val owner = lifecycleOwner ?: return
        val scene = uiState.value.activeScene ?: return
        scene.layers.filterIsInstance<LayerDefinition.Camera>().forEach { layer ->
            val surface = cameraSurfaces[layer.id] ?: return@forEach
            val controls = cameraControls.value[layer.id] ?: ProControls()
            cameraSource.bind(surface, controls, owner)
        }
    }

    fun detachStage() {
        engine.detachPreview()
    }

    // ---------------------------------------------------------------- scenes

    fun selectScene(id: String) {
        if (id == activeSceneId.value) return
        val transition = currentTransition()
        activeSceneId.value = id
        selectedLayerId.value = null
        commit(transition)
    }

    fun addScene() = viewModelScope.launch { createSceneInternal() }

    private suspend fun createSceneInternal() {
        val res = sceneResolution.value
        val index = scenes.value.size + 1
        val scene = SceneDefinition(
            id = newId("scene"),
            name = "Scene $index",
            width = res.width,
            height = res.height,
        )
        scenes.value = scenes.value + scene
        val isFirst = activeSceneId.value.isEmpty()
        activeSceneId.value = scene.id
        commit(if (isFirst) null else currentTransition())
    }

    fun deleteScene(id: String) {
        if (scenes.value.size <= 1) return
        val updated = scenes.value.filterNot { it.id == id }
        scenes.value = updated
        if (activeSceneId.value == id) {
            activeSceneId.value = updated.first().id
            selectedLayerId.value = null
        }
        commit()
    }

    fun duplicateScene(id: String) {
        val source = scenes.value.firstOrNull { it.id == id } ?: return
        val copy = source.copy(
            id = newId("scene"),
            name = source.name + " copy",
            layers = source.layers.map { it.duplicateWithNewId() },
        )
        scenes.value = scenes.value + copy
        commit()
    }

    // ---------------------------------------------------------------- layers

    fun selectLayer(id: String?) {
        selectedLayerId.value = id
        if (id != null) sheet.value = Sheet.INSPECTOR
    }

    fun addCameraLayer(facing: RenderLensFacing) {
        val layer = LayerDefinition.Camera(
            id = newId("cam"),
            name = if (facing == RenderLensFacing.FRONT) "Camera (front)" else "Camera (back)",
            lensFacing = facing,
        )
        cameraControls.value = cameraControls.value + (
            layer.id to ProControls(
                lensFacing = if (facing == RenderLensFacing.FRONT) LensFacing.FRONT else LensFacing.BACK,
            )
            )
        appendLayer(layer)
        if (rawMode.value) maybeBindRawCamera() // RAW: camera straight to PreviewView
        sheet.value = Sheet.NONE
    }

    fun onImagePicked(uri: Uri) {
        val sourceId = newId("img")
        val res = sceneResolution.value
        viewModelScope.launch(dispatchers.io) {
            try {
                val context = appContext ?: return@launch
                val bitmap = ImageLoader.loadDownscaled(context, uri)
                imageCache[sourceId] = bitmap
                engine.registerBitmapSource(sourceId, bitmap)
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val sceneAspect = res.width.toFloat() / res.height.toFloat()
                val transform = if (aspect >= sceneAspect) {
                    LayerTransform(width = 1f, height = (sceneAspect / aspect), fitMode = com.vcamstudio.engine.render.model.FitMode.FIT)
                } else {
                    LayerTransform(width = aspect / sceneAspect, height = 1f, fitMode = com.vcamstudio.engine.render.model.FitMode.FIT)
                }
                launch(dispatchers.main) {
                    appendLayer(
                        LayerDefinition.Image(id = newId("layer"), sourceId = sourceId, transform = transform),
                    )
                    sheet.value = Sheet.NONE
                }
            } catch (t: Throwable) {
                Timber.e(t, "image load failed")
                toast.value = "Could not load image: ${t.message}"
            }
        }
    }

    fun onVideoPicked(uri: Uri) {
        val layerId = newId("layer")
        val sourceId = "vid:$layerId"
        videoParams[sourceId] = VideoParams(uri)
        appendLayer(LayerDefinition.Video(id = layerId, sourceId = sourceId, name = "Video"))
        sheet.value = Sheet.NONE
    }

    fun addTextLayer(text: String) {
        val layerId = newId("txt")
        appendLayer(
            LayerDefinition.Text(id = layerId, spec = TextSpec(text = text.ifBlank { "Text" })),
        )
        sheet.value = Sheet.NONE
    }

    fun addColorLayer(argb: Int) {
        appendLayer(
            LayerDefinition.Color(id = newId("color"), color = argb, name = "Color"),
        )
        sheet.value = Sheet.NONE
    }

    fun removeLayer(id: String) {
        val scene = uiState.value.activeScene ?: return
        val layer = scene.layers.firstOrNull { it.id == id } ?: return
        when (layer) {
            is LayerDefinition.Camera -> {
                engine.closeSource(layer.id)
                cameraControls.value = cameraControls.value - id
            }
            is LayerDefinition.Video -> {
                engine.closeSource(layer.sourceId)
                releaseVideoController(layer.sourceId)
                videoParams.remove(layer.sourceId)
            }
            is LayerDefinition.Image -> engine.closeSource(layer.sourceId)
            is LayerDefinition.Text -> engine.closeSource(layer.id)
            is LayerDefinition.Color -> Unit
        }
        scenes.value = scenes.value.map { s ->
            if (s.id == scene.id) s.copy(layers = s.layers.filterNot { it.id == id }) else s
        }
        if (selectedLayerId.value == id) selectedLayerId.value = null
        commit()
    }

    fun moveLayer(id: String, up: Boolean) {
        val scene = uiState.value.activeScene ?: return
        val layers = scene.layers.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return
        // Stack renders bottom->top; "up" in UI = later in list.
        val target = if (up) index + 1 else index - 1
        if (target < 0 || target >= layers.size) return
        val item = layers.removeAt(index)
        layers.add(target, item)
        scenes.value = scenes.value.map { s -> if (s.id == scene.id) s.copy(layers = layers) else s }
        commit()
    }

    fun updateLayer(id: String, mutate: (LayerDefinition) -> LayerDefinition) {
        val scene = uiState.value.activeScene ?: return
        scenes.value = scenes.value.map { s ->
            if (s.id == scene.id) {
                s.copy(layers = s.layers.map { if (it.id == id) mutate(it) else it })
            } else {
                s
            }
        }
        commit()
    }

    fun updateText(layerId: String, spec: TextSpec) {
        updateLayer(layerId) { layer -> (layer as? LayerDefinition.Text)?.copy(spec = spec) ?: layer }
    }

    // ------------------------------------------------------------ transition

    fun setTransitionType(type: TransitionType) {
        transitionType.value = type
        engine.setTransition(currentTransition())
    }

    fun setFadeDuration(ms: Long) {
        fadeDurationMs.value = ms
        engine.setTransition(currentTransition())
    }

    private fun currentTransition(): TransitionSpec =
        TransitionSpec(transitionType.value, fadeDurationMs.value)

    // ---------------------------------------------------------------- camera

    fun updateCameraControls(layerId: String, mutate: (ProControls) -> ProControls) {
        val current = cameraControls.value[layerId] ?: return
        val updated = mutate(current)
        cameraControls.value = cameraControls.value + (layerId to updated)
        // Runtime-tunable controls apply INSTANTLY via capture-session controls
        // — a full CameraX rebind per slider tick tears down the session
        // mid-preview and crashes (round-7 "settings crash" root cause).
        cameraSource.applyRuntime(updated)
        // Only bind-time controls (WB/AF/ISO/fps) need a rebind — debounce the
        // slider storm so a drag produces ONE rebind, not dozens.
        val bindTimeChanged = current.whiteBalance != updated.whiteBalance ||
            current.focusMode != updated.focusMode ||
            current.iso != updated.iso ||
            current.fpsRange != updated.fpsRange
        if (bindTimeChanged) scheduleDebouncedRebind(layerId, updated)
    }

    private var rebindJob: kotlinx.coroutines.Job? = null

    private fun scheduleDebouncedRebind(layerId: String, controls: ProControls) {
        rebindJob?.cancel()
        rebindJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            if (rawMode.value) maybeBindRawCamera() else rebindCamera(layerId, controls)
        }
    }

    fun tapToFocus(x: Float, y: Float, viewW: Float, viewH: Float) =
        cameraSource.tapToFocus(x, y, viewW, viewH)

    // ------------------------------------------------------------------- mic

    fun toggleMic() {
        if (mic.running.value) mic.stop() else mic.start()
    }

    // -------------------------------------------------------------- recording

    fun toggleRecording() {
        when (recorder.state.value) {
            is RecordingController.State.Recording -> stopRecording()
            is RecordingController.State.Idle -> startRecording()
            else -> Unit // Starting/Stopping transitions are in flight
        }
    }

    private fun startRecording() {
        val scene = uiState.value.activeScene ?: return
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(dir, "vcam_$stamp.mp4")
        mixer.reset()
        val started = recorder.start(
            file, scene.width, scene.height,
            onSurfaceReady = { surface ->
                engine.attachRecordingOutput(surface, scene.width, scene.height)
                startAudioPump()
            },
            onFailed = { msg -> toast.value = "Recorder: $msg" },
        )
        if (!started) toast.value = "Recorder busy"
    }

    private fun stopRecording() {
        stopAudioPump()
        recorder.stop { file ->
            engine.detachRecordingOutput()
            if (file != null && file.length() > 0) {
                lastRecordingPath.value = file.absolutePath
                toast.value = "Saved ${file.name}"
            } else {
                toast.value = "Recording failed — nothing written"
            }
        }
    }

    /** 20 ms pump: mixer -> recorder AAC feed (runs while recording). */
    private fun startAudioPump() {
        audioPumpJob = viewModelScope.launch(dispatchers.io) {
            val out = ShortArray(AudioMixer.FRAME_FRAMES * 2)
            while (isActive && recorder.state.value is RecordingController.State.Recording) {
                mixer.read(out)
                recorder.offerAudio(out.copyOf())
            }
        }
    }

    private fun stopAudioPump() {
        audioPumpJob?.cancel()
        audioPumpJob = null
    }

    // ------------------------------------------------------------------ mixer

    fun setMasterGain(v: Float) = mixer.setMasterGain(v)
    fun setLimiterEnabled(v: Boolean) = mixer.setLimiterEnabled(v)
    fun setBusGain(id: AudioBusId, v: Float) = mixer.setBusGain(id, v)
    fun setBusMute(id: AudioBusId, v: Boolean) = mixer.setBusMute(id, v)
    val audioMixer: AudioMixer get() = mixer

    // -------------------------------------------------------------------- LUT

    fun onLutPicked(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val lut = CubeLutParser.parse(CubeLutParser.decode(bytes))
                val name = (uri.lastPathSegment ?: "lut")
                    .substringAfterLast('/')
                    .removeSuffix(".cube")
                    .ifBlank { "lut" }
                engine.registerLut(name, lut)
                lutNames.value = (lutNames.value - name) + name
                launch(dispatchers.main) { toast.value = "LUT \"$name\" imported (${lut.size}³)" }
            } catch (t: Throwable) {
                Timber.e(t, "LUT import failed")
                launch(dispatchers.main) { toast.value = "LUT import failed: ${t.message}" }
            }
        }
    }

    fun setLayerLut(layerId: String, lutName: String?) {
        updateLayer(layerId) { def ->
            when (def) {
                is LayerDefinition.Camera -> def.copy(effects = def.effects.copy(lutId = lutName))
                is LayerDefinition.Image -> def.copy(effects = def.effects.copy(lutId = lutName))
                is LayerDefinition.Video -> def.copy(effects = def.effects.copy(lutId = lutName))
                is LayerDefinition.Text -> def.copy(effects = def.effects.copy(lutId = lutName))
                is LayerDefinition.Color -> def.copy(effects = def.effects.copy(lutId = lutName))
            }
        }
    }

    fun clearLastRecording() {
        lastRecordingPath.value = null
    }

    // ---------------------------------------------------------------- sheets

    fun setSheet(value: Sheet) {
        sheet.value = value
    }

    fun dismissToast() {
        toast.value = null
    }

    // -------------------------------------------------------------- settings

    fun setSceneResolution(resolution: SceneResolution) {
        viewModelScope.launch {
            settings.setSceneResolution(resolution)
            sceneResolution.value = resolution
            val activeId = activeSceneId.value
            scenes.value = scenes.value.map {
                if (it.id == activeId) it.copy(width = resolution.width, height = resolution.height) else it
            }
            commit()
        }
    }

    // ------------------------------------------------------------ diagnostics

    fun forceRecoveryTest() {
        engine.requestRecovery("manual test from diagnostics")
    }

    fun diagnosticsDump(): String = engine.dump()

    /** DEV DIAGNOSTIC (round 16A): render external sources as a UV gradient. */
    val uvDebugPass = MutableStateFlow(false)

    fun setUvDebugPass(enabled: Boolean) {
        uvDebugPass.value = enabled
        engine.setUvDebugPass(enabled)
    }

    /** DEV TEST (round 16D-3): fresh VBO/VAO draw path. */
    val vboDrawPass = MutableStateFlow(false)

    fun setVboDrawPass(enabled: Boolean) {
        vboDrawPass.value = enabled
        engine.setVboDrawPass(enabled)
    }

    // -------------------------------------------------------------- internals

    private fun appendLayer(layer: LayerDefinition) {
        val activeId = activeSceneId.value
        scenes.value = scenes.value.map { s ->
            if (s.id == activeId) s.copy(layers = s.layers + layer) else s
        }
        selectedLayerId.value = layer.id
        commit()
    }

    /** Registers bitmap-backed sources (text raster, imported images) before setScene. */
    private fun registerBitmapSources(scene: SceneDefinition) {
        for (layer in scene.layers) {
            when (layer) {
                is LayerDefinition.Text -> {
                    val bmp = TextRasterizer.rasterize(layer.spec, scene.width, scene.height)
                    engine.registerBitmapSource(layer.id, bmp)
                }
                is LayerDefinition.Image -> {
                    imageCache[layer.sourceId]?.let { engine.registerBitmapSource(layer.sourceId, it) }
                }
                else -> Unit
            }
        }
    }

    private var lastCommittedScene: SceneDefinition? = null

    private fun commit(transition: TransitionSpec? = null) {
        val scene = scenes.value.firstOrNull { it.id == activeSceneId.value } ?: return
        registerBitmapSources(scene)
        if (transition != null) engine.setTransition(transition)
        // Identical-scene re-commits (slider bursts, collector echoes) are
        // dropped engine-side too; skipping here avoids re-registering bitmaps.
        if (scene != lastCommittedScene) {
            engine.setScene(scene)
            lastCommittedScene = scene
        }
    }

    private fun onExternalSourceReady(sourceId: String, surface: android.view.Surface) {
        val scene = uiState.value.activeScene ?: return
        val owner = lifecycleOwner
        if (owner == null) {
            Timber.w("EXTERNAL_SOURCE_DROPPED_NO_LIFECYCLE $sourceId")
            return
        }
        val cameraLayer = scene.layers.filterIsInstance<LayerDefinition.Camera>().firstOrNull { it.id == sourceId }
        if (cameraLayer != null) {
            cameraSurfaces[sourceId] = surface
            if (rawMode.value) {
                Timber.i("CAMERA_ENGINE_BIND_SKIPPED_RAW $sourceId")
                return
            }
            val controls = cameraControls.value[sourceId] ?: ProControls()
            cameraSource.bind(surface, controls, owner)
            return
        }
        val videoLayer = scene.layers.filterIsInstance<LayerDefinition.Video>().firstOrNull { it.sourceId == sourceId }
        val params = videoParams[sourceId]
        if (videoLayer != null && params != null) {
            releaseVideoController(sourceId)
            val context = appContext ?: return
            val controller = VideoLayerController(
                context, sourceId,
                MixerAudioTap { pcm, ch, _ -> mixer.offerPcm(AudioBusId.MEDIA, pcm, ch) },
            )
            controller.onError = { msg -> toast.value = "Video playback error: $msg" }
            controller.onVideoSizeChanged = { w, h, rot ->
                engine.resizeSource(sourceId, w, h)
                // ExoPlayer contract (same rule as the camera layer):
                // sampling rotation == unappliedRotationDegrees, applied
                // post-ST by SourceUvMath.
                val uvRot = rot.toFloat()
                val layerId = videoLayer.id
                updateLayer(layerId) { def ->
                    if (def is LayerDefinition.Video && def.transform.uvRotationDeg != uvRot) {
                        def.copy(transform = def.transform.copy(uvRotationDeg = uvRot))
                    } else def
                }
            }
            videoControllers[sourceId] = controller
            controller.load(
                surface = surface,
                uri = params.uri,
                loop = params.loop,
                muted = params.muted,
                volume = params.volume,
                speed = params.speed,
            )
        }
    }

    private fun onExternalSourceReleased(sourceId: String) {
        if (sourceId.startsWith("vid:")) {
            releaseVideoController(sourceId)
        } else {
            cameraSource.unbind()
        }
    }

    private fun rebindCamera(layerId: String, controls: ProControls) {
        if (rawMode.value) {
            maybeBindRawCamera()
            return
        }
        val owner = lifecycleOwner ?: return
        val surface = cameraSurfaces[layerId] ?: return
        cameraSource.bind(surface, controls, owner)
    }

    private fun releaseVideoController(sourceId: String) {
        videoControllers.remove(sourceId)?.release()
    }

    private val appContext: android.content.Context?
        get() = lifecycleOwner?.let { it as? android.content.Context }

    private fun newId(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    override fun onCleared() {
        engine.onExternalSourceReady = null
        engine.onExternalSourceReleased = null
        audioPumpJob?.cancel()
        if (recorder.isBusy) recorder.stop()
        videoControllers.values.forEach { it.release() }
        videoControllers.clear()
        mic.stop()
        // Engine is app-scoped; it stays alive across configuration changes.
    }
}

private fun LayerDefinition.duplicateWithNewId(): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(id = id + "-copy")
    is LayerDefinition.Image -> this // sourceId shared is fine (bitmap already registered)
    is LayerDefinition.Video -> this
    is LayerDefinition.Text -> this
    is LayerDefinition.Color -> copy(id = id + "-copy")
}
