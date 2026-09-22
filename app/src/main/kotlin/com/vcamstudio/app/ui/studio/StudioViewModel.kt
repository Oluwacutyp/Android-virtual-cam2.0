package com.vcamstudio.app.ui.studio

import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcamstudio.app.settings.SceneResolution
import com.vcamstudio.app.settings.StudioSettings
import com.vcamstudio.core.dispatch.DispatcherProvider
import com.vcamstudio.engine.audio.MicLevelMonitor
import com.vcamstudio.engine.capture.CameraSource
import com.vcamstudio.engine.capture.LensFacing
import com.vcamstudio.engine.capture.ProControls
import com.vcamstudio.engine.media.ImageLoader
import com.vcamstudio.engine.media.VideoLayerController
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {

    enum class Sheet { NONE, INSPECTOR, DIAGNOSTICS, SETTINGS }

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
    private val sceneResolution = MutableStateFlow(SceneResolution.P_720)

    private val videoControllers = LinkedHashMap<String, VideoLayerController>()
    private val videoParams = LinkedHashMap<String, VideoParams>()
    private val imageCache = LinkedHashMap<String, android.graphics.Bitmap>()

    private var lifecycleOwner: LifecycleOwner? = null

    val uiState: StateFlow<UiState> = combine(
        combine(scenes, activeSceneId, selectedLayerId, sheet) { s, a, sel, sh ->
            Quad(s, a, sel, sh)
        },
        combine(cameraSource.state, cameraControls, transitionType, fadeDurationMs) { cs, cc, tt, fd ->
            Quad(cs, cc, tt, fd)
        },
        combine(engine.diagnostics, engine.health, mic.levelRms, mic.running) { d, h, lvl, run ->
            Quad(d, h, lvl, run)
        },
        sceneResolution,
        toast,
    ) { core, cameraStuff, diagStuff, resolution, msg ->
        UiState(
            scenes = core.a,
            activeSceneId = core.b,
            selectedLayerId = core.c,
            sheet = core.d,
            cameraState = cameraStuff.a,
            cameraControls = cameraStuff.b,
            transitionType = cameraStuff.c,
            fadeDurationMs = cameraStuff.d,
            diagnostics = diagStuff.a,
            health = diagStuff.b,
            micLevel = diagStuff.c,
            micRunning = diagStuff.d,
            sceneResolution = resolution,
            toast = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    init {
        // Engine -> app: external surfaces for camera/video sources.
        engine.onExternalSourceReady = { sourceId, surface, _, _ ->
            onExternalSourceReady(sourceId, surface)
        }
        engine.onExternalSourceReleased = { sourceId ->
            onExternalSourceReleased(sourceId)
        }

        viewModelScope.launch {
            settings.sceneResolution.collect { sceneResolution.value = it }
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
        rebindCamera(layerId, updated)
    }

    fun tapToFocus(x: Float, y: Float, viewW: Float, viewH: Float) =
        cameraSource.tapToFocus(x, y, viewW, viewH)

    // ------------------------------------------------------------------- mic

    fun toggleMic() {
        if (mic.running.value) mic.stop() else mic.start()
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

    private fun commit(transition: TransitionSpec? = null) {
        val scene = scenes.value.firstOrNull { it.id == activeSceneId.value } ?: return
        registerBitmapSources(scene)
        if (transition != null) engine.setTransition(transition)
        engine.setScene(scene)
    }

    private fun onExternalSourceReady(sourceId: String, surface: android.view.Surface) {
        val scene = uiState.value.activeScene ?: return
        val owner = lifecycleOwner ?: return
        val cameraLayer = scene.layers.filterIsInstance<LayerDefinition.Camera>().firstOrNull { it.id == sourceId }
        if (cameraLayer != null) {
            val controls = cameraControls.value[sourceId] ?: ProControls()
            cameraSource.bind(surface, controls, owner)
            return
        }
        val videoLayer = scene.layers.filterIsInstance<LayerDefinition.Video>().firstOrNull { it.sourceId == sourceId }
        val params = videoParams[sourceId]
        if (videoLayer != null && params != null) {
            releaseVideoController(sourceId)
            val context = appContext ?: return
            val controller = VideoLayerController(context, sourceId)
            controller.onError = { msg -> toast.value = "Video playback error: $msg" }
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
        val owner = lifecycleOwner ?: return
        // Rebind with the existing engine surface: engine keeps it registered.
        val scene = uiState.value.activeScene ?: return
        val layer = scene.layers.filterIsInstance<LayerDefinition.Camera>().firstOrNull { it.id == layerId } ?: return
        // The surface is engine-owned; request a fresh handle through engine by
        // re-setting the scene would drop the source. Instead reuse CameraSource
        // rebind with the surface it already has:
        cameraSource.rebindWithCurrentSurface(controls, owner)
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
