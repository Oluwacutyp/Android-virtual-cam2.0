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
import com.vcamstudio.engine.audio.MasterMonitor
import com.vcamstudio.engine.audio.MicLevelMonitor
import com.vcamstudio.engine.capture.CameraSource
import com.vcamstudio.engine.capture.LensFacing
import com.vcamstudio.engine.capture.ProControls
import com.vcamstudio.engine.media.ImageLoader
import com.vcamstudio.engine.media.MixerAudioTap
import com.vcamstudio.engine.media.VideoLayerController
import com.vcamstudio.app.recording.RecordingStore
import com.vcamstudio.engine.output.RecordingController
import com.vcamstudio.engine.output.RecordingOutput
import com.vcamstudio.engine.render.geometry.StClass
import com.vcamstudio.engine.render.geometry.StMirror
import com.vcamstudio.engine.render.geometry.StOrientation
import com.vcamstudio.engine.render.geometry.StCompensation
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
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
    private val modelManager: com.vcamstudio.engine.aicore.ModelManager,
    private val faceDetection: com.vcamstudio.engine.aiface.FaceDetectionController,
    private val mic: MicLevelMonitor,
    private val settings: StudioSettings,
    private val dispatchers: DispatcherProvider,
    private val mixer: AudioMixer,
    private val recorder: RecordingController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    enum class Sheet { NONE, INSPECTOR, DIAGNOSTICS, SETTINGS, MIXER, RECORDINGS, MODELS }

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
        val lastRecording: SavedRecording? = null,
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
    private val lastRecording = MutableStateFlow<SavedRecording?>(null)

    /** Round-31: in-app library — same MediaStore rows the gallery shows. */
    private val _recordings = MutableStateFlow<List<RecordingStore.Item>>(emptyList())
    val recordings: StateFlow<List<RecordingStore.Item>> = _recordings.asStateFlow()

    // ---------------- Phase 2: model manager + SCRFD detection ----------------

    val modelStates: StateFlow<Map<String, com.vcamstudio.engine.aicore.ModelManager.ModelState>> =
        modelManager.states

    /** Whether the active scene shows a camera layer (detection precondition). */
    private val _hasCameraLayer = MutableStateFlow(false)
    val hasCameraLayer: StateFlow<Boolean> = _hasCameraLayer.asStateFlow()

    /** Face box (upright-frame normalized, front-mirror applied) for the debug overlay. */
    private val _faceOverlay = MutableStateFlow<com.vcamstudio.engine.aiface.FaceBox?>(null)
    val faceOverlay: StateFlow<com.vcamstudio.engine.aiface.FaceBox?> = _faceOverlay.asStateFlow()

    val scrfdPhase: StateFlow<com.vcamstudio.engine.aiface.FaceDetectionController.Phase> =
        faceDetection.phase

    val scrfdStats: StateFlow<com.vcamstudio.engine.aiface.FaceDetectionController.Stats> =
        faceDetection.stats

    val scrfdModelStates: StateFlow<Map<String, com.vcamstudio.engine.aicore.ModelManager.ModelState>>
        get() = modelManager.states

    /** NNAPI dev toggle (default OFF per mandate) — applied at session (re)build. */
    val scrfdNnapi = MutableStateFlow(false)

    /** Round 44: DEV "enable SCRFD session" (persisted, default OFF). The
     *  live value drives the UI (overlay/dump gating, row label); the
     *  BOOT-time value drives the session attempt ("requires restart"). */
    val scrfdSessionDev = MutableStateFlow(false)

    /** Round 44 (r33 FIX B): DEV "monitor mic" (persisted, default OFF) —
     *  UI mirror; the mixer holds the effective value. */
    val monitorMicEnabled = MutableStateFlow(false)

    /** Round 45 (owner): DEV "monitor media while recording" (persisted,
     *  default OFF) — UI mirror; consulted synchronously at REC arm. */
    val monitorMediaRecDev = MutableStateFlow(false)

    /** Round 45: monitor enablement of MEDIA/MUSIC before the REC mute —
     *  restored verbatim at disarm (and on the failed-start path). */
    private var recMonitorPrevMediaEnabled = true
    private var recMonitorPrevMusicEnabled = true

    private var scrfdFailureToasted = false

    fun downloadModel(id: String) = modelManager.download(id)
    fun deleteModel(id: String) = modelManager.delete(id)
    fun licenseSeen(id: String) = modelManager.licenseSeen(id)
    fun markLicenseSeen(id: String) = modelManager.markLicenseSeen(id)

    fun setScrfdNnapi(enabled: Boolean) {
        scrfdNnapi.value = enabled
        faceDetection.setNnapi(enabled)
    }

    /** Round 44 (owner decision 1): DEV toggle — persisted only; the session
     *  attempt happens at the NEXT process boot ("requires restart"). */
    fun setScrfdDevSession(enabled: Boolean) {
        viewModelScope.launch { settings.setScrfdSessionEnabled(enabled) }
        Timber.i("SCRFD_DEV_TOGGLE enabled=%s effective=next-boot", enabled)
    }

    /** Round 44 (r33 FIX B): DEV "monitor mic" (default OFF, headphones). */
    fun setMonitorMic(enabled: Boolean) {
        viewModelScope.launch { settings.setMonitorMic(enabled) }
        // mixer + AUDIO_MONITOR log happen in the settings collector —
        // DataStore is the single source of truth.
    }

    /** Round 45 (owner): DEV "monitor media while recording" (default OFF). */
    fun setMonitorMediaRec(enabled: Boolean) {
        viewModelScope.launch { settings.setMonitorMediaWhileRecording(enabled) }
    }

    /** Sentinel file: armed right before ORT is touched, cleared once the
     *  phase collector proves the process survived session creation. A boot
     *  that finds it means the previous attempt died NATIVELY. */
    private fun scrfdSentinel(): File = File(context.filesDir, "scrfd_session_sentinel")

    private fun tryScrfdDevSession(path: String?) {
        if (path == null) return
        val sentinel = scrfdSentinel()
        if (sentinel.exists()) {
            runCatching { sentinel.delete() }
            scrfdSessionDev.value = false
            viewModelScope.launch { settings.setScrfdSessionEnabled(false) }
            Timber.w("SCRFD_DEV_AUTODISABLE reason=sentinel-leftover")
            toast.value = "SCRFD dev session crashed previously — toggle disabled"
            return
        }
        runCatching { sentinel.writeText("armed ts=${System.currentTimeMillis()} path=$path\n") }
        Timber.i("SCRFD_SENTINEL_ARMED path=%s", path)
        faceDetection.setModel(path)
    }

    private fun clearScrfdSentinel(why: String) {
        val s = scrfdSentinel()
        if (s.exists() && runCatching { s.delete() }.getOrDefault(false)) {
            Timber.i("SCRFD_SENTINEL_CLEARED reason=%s", why)
        }
    }

    /** Never-throw (round 43): called from guarded collectors AND the UI —
     *  an analyzer rebind failure must log, not crash the main thread.
     *  Round 44: the analyzer additionally requires the DEV session toggle —
     *  with it off, NOTHING loads the model (owner decisions 1+2). */
    private fun syncDetection() {
        runCatching {
            val scrfdReady = modelManager.isReady("scrfd_10g_bnkps")
            cameraSource.setAnalysisAnalyzer(
                if (scrfdReady && scrfdSessionDev.value && _hasCameraLayer.value) faceDetection.analyzer else null,
            )
        }.onFailure { t -> Timber.e(t, "MODEL_OBSERVE_FAIL src=syncDetection") }
    }

    private val videoControllers = LinkedHashMap<String, VideoLayerController>()
    private val videoParams = LinkedHashMap<String, VideoParams>()
    private val imageCache = LinkedHashMap<String, android.graphics.Bitmap>()
    /** Engine-owned surfaces per camera layer id, kept for fast rebinds. */
    private val cameraSurfaces = LinkedHashMap<String, android.view.Surface>()

    /**
     * Round-28: last ST-class-derived compensation per layer id. Seeds the
     * rebind path (camera Bound collect) so lifecycle bounces don't flash
     * mis-oriented frames before the next ST_CLASS event.
     */
    private val lastOrientationComp = LinkedHashMap<String, StCompensation>()

    /**
     * Round-25: last classified ST class per source id (cameras AND videos).
     * Display-rotation changes re-run compensation from these without
     * waiting for a new ST event.
     */
    private val lastStClassBySource = LinkedHashMap<String, StClass>()

    /** Round-25: last known display rotation in DEGREES (null = never read). */
    private var lastDisplayRotDeg: Int? = null

    /** Round-25: display rotation value already reported, to log changes only. */
    private var loggedDisplayRotDeg: Int = -1

    private var lifecycleOwner: LifecycleOwner? = null

    /**
     * Round-30: the audible master monitor + the ONE mix pump. The mixer is
     * single-consumer: a single loop reads mixed frames and feeds BOTH the
     * monitor (speaker) and — while recording — the encoder. Video-layer
     * audio therefore obeys the MEDIA bus mute end to end.
     */
    private val masterMonitor = MasterMonitor()
    private var audioOutJob: kotlinx.coroutines.Job? = null

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
            sceneResolution, lutNames, lastRecording, mixer.masterGain, mixer.limiterEnabled,
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
            lastRecording = extra.c,
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
        // Round-28: the engine classifies every producer ST (ST_CLASS event);
        // the camera-layer UV compensation derives from that class — the
        // single canonical rotation place (round-24 mandate, supersedes the
        // r18 sensor-arithmetic revert).
        engine.onSourceOrientationClassified = { sourceId, rotCwDeg, mirror ->
            onSourceOrientationClassified(sourceId, rotCwDeg, mirror)
        }

        // Mic PCM (48 kHz mono) feeds the mixer MIC bus for recording.
        Timber.i("AUDIO_ROUTE source=mic bus=mic")
        mic.pcmSink = { pcm, _ -> mixer.offerPcm(AudioBusId.MIC, pcm, 1) }

        // Round-30: RECORDER_STATE transitions surface in the app log/dump.
        recorder.eventSink = { msg -> Timber.i("%s", msg) }

        // Round-30: the ONE mix pump — always on. Feeds the master monitor
        // (speaker; write() blocks -> real-time pacing) and the recorder
        // while recording. Recording no longer owns a pump.
        // Round 44 (r33 FIX B): monitor routing at graph build — the
        // RECORDER keeps the full mix; the SPEAKER hears MEDIA+MUSIC+TTS,
        // never the MIC unless the DEV "monitor mic" toggle is on.
        viewModelScope.launch {
            val micAtBoot = runCatching { settings.monitorMic.first() }.getOrDefault(false)
            mixer.setMonitorMic(micAtBoot)
            monitorMicEnabled.value = micAtBoot
            for (bus in com.vcamstudio.engine.audio.AudioBusId.entries) {
                val enabled = bus != com.vcamstudio.engine.audio.AudioBusId.MIC || micAtBoot
                Timber.i("AUDIO_MONITOR bus=%s enabled=%s", bus, enabled)
            }
        }
        viewModelScope.launch {
            settings.monitorMic.collect { v ->
                monitorMicEnabled.value = v
                mixer.setMonitorMic(v)
                Timber.i("AUDIO_MONITOR bus=MIC enabled=%s (dev toggle)", v)
            }
        }
        viewModelScope.launch {
            settings.monitorMediaWhileRecording.collect { v ->
                monitorMediaRecDev.value = v
                Timber.i("AUDIO_MONITOR dev=rec-media value=%s", v)
            }
        }
        // Round 45: a previous process could have died mid-recording and
        // left MEDIA/MUSIC muted from the monitor. A fresh VM is never
        // recording -> restore the defaults.
        mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MEDIA, true)
        mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MUSIC, true)
        audioOutJob = viewModelScope.launch(dispatchers.io) {
            val out = ShortArray(AudioMixer.FRAME_FRAMES * 2)
            val mon = ShortArray(AudioMixer.FRAME_FRAMES * 2)
            while (isActive) {
                mixer.readInto(out, mon)
                val monitored = masterMonitor.write(mon)
                if (recorder.state.value is RecordingController.State.Recording) {
                    recorder.offerAudio(out.copyOf())
                }
                if (!monitored) kotlinx.coroutines.delay(15)
            }
        }

        // Phase 2: SCRFD lifecycle — model presence gates the detector; a
        // camera layer on the active scene gates the analysis stream.
        // Phase 2: SCRFD lifecycle. Round 44 (owner decision 1): the
        // download-complete path NO LONGER touches ORT — the model lands on
        // disk and the row reports "Ready (not active)"; NOTHING loads it.
        // The ORT session is attempted ONLY if the DEV toggle was ON at
        // process boot (mandate: requires restart), behind the native-crash
        // sentinel (tryScrfdDevSession). Round 42 guards stay.
        viewModelScope.launch {
            val scrfdDevAtBoot = runCatching { settings.scrfdSessionEnabled.first() }.getOrDefault(false)
            scrfdSessionDev.value = scrfdDevAtBoot
            var lastScrfdReady = false
            var devSessionAttempted = false
            modelManager.states.collect {
                // Act on READY transitions only — progress ticks arrive ~1/s
                // during downloads and must not churn the ONNX session.
                runCatching {
                    val ready = modelManager.isReady("scrfd_10g_bnkps")
                    if (ready != lastScrfdReady) {
                        lastScrfdReady = ready
                        if (ready) {
                            if (scrfdDevAtBoot && !devSessionAttempted) {
                                devSessionAttempted = true
                                tryScrfdDevSession(modelManager.readyFile("scrfd_10g_bnkps")?.absolutePath)
                            }
                            // else (r44): MODEL_DOWNLOADED — the file sits on
                            // disk; no OrtSession, no ScrfdDetector, ever.
                        } else {
                            faceDetection.setModel(null)
                        }
                    }
                    syncDetection()
                }.onFailure { t -> Timber.e(t, "MODEL_OBSERVE_FAIL src=states") }
            }
        }
        viewModelScope.launch {
            settings.scrfdSessionEnabled.collect { scrfdSessionDev.value = it }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(scenes, activeSceneId) { s, id ->
                s.firstOrNull { it.id == id }?.layers?.any { it is LayerDefinition.Camera } ?: false
            }.collect {
                runCatching {
                    _hasCameraLayer.value = it
                    syncDetection()
                }.onFailure { t -> Timber.e(t, "MODEL_OBSERVE_FAIL src=camera-layer") }
            }
        }
        viewModelScope.launch {
            faceDetection.stats.collect { stats ->
                runCatching {
                    val box = stats.box ?: return@runCatching
                    val mirrored = cameraSource.isFrontCamera()
                    _faceOverlay.value = if (mirrored) {
                        box.copy(x1 = 1f - box.x2, x2 = 1f - box.x1)
                    } else {
                        box
                    }
                }.onFailure { t -> Timber.e(t, "MODEL_OBSERVE_FAIL src=stats") }
            }
        }
        viewModelScope.launch {
            // one-time toast on session failure (mandate)
            faceDetection.phase.collect { phase ->
                runCatching {
                    // Round 44: the process SURVIVED the session-create
                    // attempt (session built, or it failed in Java) — the
                    // native-crash sentinel can go. A NATIVE death kills the
                    // process before any phase lands, leaving it armed.
                    if (phase == com.vcamstudio.engine.aiface.FaceDetectionController.Phase.RUNNING ||
                        phase == com.vcamstudio.engine.aiface.FaceDetectionController.Phase.SESSION_FAILED
                    ) {
                        clearScrfdSentinel(
                            if (phase == com.vcamstudio.engine.aiface.FaceDetectionController.Phase.RUNNING) {
                                "session-running"
                            } else {
                                "session-failed-java"
                            },
                        )
                    }
                    if (phase == com.vcamstudio.engine.aiface.FaceDetectionController.Phase.SESSION_FAILED &&
                        !scrfdFailureToasted
                    ) {
                        scrfdFailureToasted = true
                        toast.value = "SCRFD session failed — detection disabled"
                    }
                }.onFailure { t -> Timber.e(t, "MODEL_OBSERVE_FAIL src=phase") }
            }
        }

        viewModelScope.launch {
            settings.sceneResolution.collect { sceneResolution.value = it }
        }
        viewModelScope.launch {
            // Camera bound -> re-apply the LAST ST-class-derived orientation
            // compensation if one is known for this camera layer (rebind /
            // lifecycle path: the engine re-classifies within a frame or two,
            // but the seed avoids any mis-oriented first frames). The
            // rotation itself derives EXCLUSIVELY from ST_CLASS events
            // (round-24 mandate) — no sensor arithmetic here anymore.
            cameraSource.state.collect { st ->
                if (st !is CameraSource.State.Bound) return@collect
                var changed = false
                scenes.value = scenes.value.map { s ->
                    s.copy(layers = s.layers.map { l ->
                        if (l is LayerDefinition.Camera) {
                            val comp = lastOrientationComp[l.id] ?: return@map l
                            if (l.transform.uvRotationDeg != comp.uvRotDeg ||
                                l.transform.mirrorX != comp.mirrorX
                            ) {
                                changed = true
                                l.copy(transform = l.transform.copy(uvRotationDeg = comp.uvRotDeg, mirrorX = comp.mirrorX))
                            } else l
                        } else l
                    })
                }
                if (changed) {
                    Timber.i("ORIENT_SEED re-applied last ST-class compensation on camera bind")
                    commit()
                }
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
        // Round-25: activity (re)start may follow a rotation/recreate —
        // re-read display rotation and re-run compensation from the last
        // classified ST classes before frames land.
        onDisplayRotationMaybeChanged()
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

    /**
     * Round-29: deletion-proof scene naming. Count-based naming produced
     * duplicates on the device (delete "Scene 1" -> survivor "Scene 2";
     * create -> size+1 = "Scene 2" again). Name = max existing
     * "Scene <N>" index + 1, so the sequence is always Scene 1, 2, 3, ...
     * regardless of deletions.
     */
    private fun nextSceneName(): String {
        val maxIndex = scenes.value.maxOfOrNull { scene ->
            Regex("^Scene (\\d+)$").find(scene.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } ?: 0
        return "Scene ${maxIndex + 1}"
    }

    private suspend fun createSceneInternal() {
        val res = sceneResolution.value
        val scene = SceneDefinition(
            id = newId("scene"),
            name = nextSceneName(),
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
        // Round-29: a stale/empty activeSceneId used to SILENTLY kill the REC
        // tap (button read as disabled on device). Recover instead: fall back
        // to the first scene and repair the active id so recording always
        // starts when a scene exists.
        var scene = uiState.value.activeScene
        if (scene == null) {
            scene = scenes.value.firstOrNull() ?: run {
                toast.value = "Nothing to record — create a scene first"
                return
            }
            Timber.w("REC_FALLBACK activeSceneId stale -> recovering to %s", scene.name)
            activeSceneId.value = scene.id
        }
        // Round-31: output goes to the PUBLIC media library (MediaStore,
        // Movies/VCamStudio) so clips are visible in gallery/Files with no
        // share-through. Legacy (<29) uses the public Movies dir.
        val target = try {
            RecordingStore.createTarget(context, RecordingStore.newName())
        } catch (t: Throwable) {
            Timber.e(t, "REC_TARGET failed")
            toast.value = "Storage error: ${t.message}"
            return
        }
        mixer.reset()
        // Round 45 (owner): while recording, MEDIA (and MUSIC — same pattern,
        // Phase-3-ready) leave the MONITOR ONLY. The speaker->mic feedback
        // path was double-stamping the video's audio into the file. The
        // recorder still receives the full mix — it is fed before the split.
        recMonitorPrevMediaEnabled = mixer.isBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MEDIA)
        recMonitorPrevMusicEnabled = mixer.isBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MUSIC)
        if (monitorMediaRecDev.value) {
            Timber.i("AUDIO_REC_MONITOR media_enabled=true reason=arm")
        } else {
            mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MEDIA, false)
            mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MUSIC, false)
            Timber.i("AUDIO_REC_MONITOR media_enabled=false reason=arm")
        }
        val started = recorder.start(
            target.output, scene.width, scene.height,
            onSurfaceReady = { surface ->
                engine.attachRecordingOutput(surface, scene.width, scene.height)
                Timber.i("RECORDER_STATE SURFACE_ATTACHED %sx%s", scene.width, scene.height)
            },
            onFailed = { msg ->
                target.uri?.let { RecordingStore.discardPending(context, it) }
                toast.value = "Recorder: $msg"
            },
        )
        if (!started) {
            mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MEDIA, recMonitorPrevMediaEnabled)
            mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MUSIC, recMonitorPrevMusicEnabled)
            Timber.i("AUDIO_REC_MONITOR media_enabled=%b reason=disarm", recMonitorPrevMediaEnabled)
            target.uri?.let { RecordingStore.discardPending(context, it) }
            toast.value = "Recorder busy"
        }
    }

    private fun stopRecording() {
        // Round 45 (owner): disarm restores the pre-REC monitor state.
        mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MEDIA, recMonitorPrevMediaEnabled)
        mixer.setBusEnabled(com.vcamstudio.engine.audio.AudioBusId.MUSIC, recMonitorPrevMusicEnabled)
        Timber.i("AUDIO_REC_MONITOR media_enabled=%b reason=disarm", recMonitorPrevMediaEnabled)
        recorder.stop { output ->
            engine.detachRecordingOutput()
            when (output) {
                is RecordingOutput.FdOutput -> {
                    RecordingStore.publish(context, output.uri)
                    lastRecording.value = SavedRecording(output.uri, output.displayName)
                    toast.value = "Saved ${output.displayName} to Movies/VCamStudio"
                    refreshRecordings()
                }
                is RecordingOutput.FileOutput -> {
                    val f = output.file
                    if (f.exists() && f.length() > 0) {
                        RecordingStore.scan(context, f)
                        lastRecording.value =
                            SavedRecording(RecordingStore.shareUriFor(context, f), f.name)
                        toast.value = "Saved ${f.name}"
                        refreshRecordings()
                    } else {
                        toast.value = "Recording failed — nothing written"
                    }
                }
                null -> toast.value = "Recording failed — nothing written"
            }
        }
    }

    // ---------------------------------------------------- recordings library

    /** Refreshes the in-app list from MediaStore (Movies/VCamStudio, vcam_*). */
    fun refreshRecordings() {
        viewModelScope.launch(dispatchers.io) {
            _recordings.value = RecordingStore.queryLibrary(context)
        }
    }

    /** Plays a library clip through the system player (content Uri, granted read). */
    fun playRecording(item: RecordingStore.Item) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "video/mp4")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure { toast.value = "No video player found" }
    }

    /** Shares a library clip (content Uri, granted read) via the system chooser. */
    fun shareRecording(item: RecordingStore.Item) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, item.uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Share recording")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { toast.value = "Share failed" }
    }

    // (Round-30: the record-time pump was REPLACED by the always-on master
    // mix pump in init — one consumer for the mixer, feeding monitor + rec.)

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
        lastRecording.value = null
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

    fun diagnosticsDump(): String =
        engine.dump() +
            // Round 44: SCRFD stats are conditional on the DEV session toggle
            // (owner decision 2); MODEL_SECTION always reports.
            (if (scrfdSessionDev.value) {
                "\n\nSCRFD_SECTION\n  " + faceDetection.dumpSection().replace("\n", "\n  ")
            } else {
                ""
            }) +
            "\n\nMODEL_SECTION\n  " +
            modelManager.dumpSection().replace("\n", "\n  ")

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

    // Round 22: the round-17 TRIANGLES toggle is deleted — TRIANGLES is the
    // only draw path in the engine (probe-wedge verdict: strip wedges on
    // Adreno 730, triangles do not).

    /** DEV TEST (round 27): static scene-FBO content — blit-and-swap only. */
    val staticFboContent = MutableStateFlow(false)

    fun setStaticFboContent(enabled: Boolean) {
        staticFboContent.value = enabled
        engine.setStaticFboContent(enabled)
    }

    /** DEV TEST (round 17C): render layers straight to the EGL surface. */
    val directSurfacePass = MutableStateFlow(false)

    fun setDirectSurfacePass(enabled: Boolean) {
        directSurfacePass.value = enabled
        engine.setDirectSurfacePass(enabled)
    }

    /** DEV BISECT (round 19): T1..T5; T6 baseline = 0 (all off). */
    val bisectLevel = MutableStateFlow(0)

    fun setBisectLevel(level: Int) {
        bisectLevel.value = level
        engine.setBisectLevel(level)
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
            // Round-30: video audio routes through the MEDIA bus (one graph:
            // every source feeds exactly one bus; buses feed the master
            // limiter). The tap feeds the bus with the LAYER's own
            // volume/mute applied; the player's direct device output is
            // silenced (load pins player.volume=0 when tapped — volume in
            // media3 applies at the AudioTrack, AFTER the tee, so the tap
            // still receives full-scale PCM). Mutations of the layer fader
            // re-read videoParams here — no stale capture.
            Timber.i("AUDIO_ROUTE source=video sourceId=%s bus=media", sourceId)
            val controller = VideoLayerController(
                context, sourceId,
                MixerAudioTap { pcm, ch, _ ->
                    val p = videoParams[sourceId]
                    val vol = if (p?.muted == true) 0f else (p?.volume ?: 1f).coerceIn(0f, 1f)
                    if (vol >= 0.999f) {
                        mixer.offerPcm(AudioBusId.MEDIA, pcm, ch)
                    } else if (vol > 0.001f) {
                        val scaled = ShortArray(pcm.size)
                        for (i in pcm.indices) {
                            scaled[i] = (pcm[i] * vol).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        mixer.offerPcm(AudioBusId.MEDIA, scaled, ch)
                    }
                },
            )
            controller.onError = { msg -> toast.value = "Video playback error: $msg" }
            controller.onVideoSizeChanged = { w, h, _ ->
                engine.resizeSource(sourceId, w, h)
                // Round-25: the separate metadata-UV math is DELETED — video
                // orientation routes through the SAME ST-class transformFromClass()
                // (the round-28 sole derivation; ST_CLASS event -> applyOrientation). Metadata
                // dims still drive the source aspect via resizeSource.
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

    /**
     * Round-25 (owner "ROUND 25 — ROTATION. SIGN FLIP + VIDEO ROUTING"):
     * the engine classified this source's producer ST into a new orientation
     * class. Derive the layer UV compensation from the class as a pure
     * function — front selfie nets upright + mirrored, back camera and video
     * net upright, unmirrored — with the DISPLAY rotation folded into the
     * rot component (re-read here, so a rotation that happened before this
     * event is already accounted for). ONE formula, ONE place, for cameras
     * AND video (the separate video metadata-UV math is deleted).
     */
    private fun onSourceOrientationClassified(sourceId: String, rotCwDeg: Int, mirrorTag: String) {
        val mirror = StMirror.fromTag(mirrorTag) ?: return
        val displayDeg = readDisplayRotationDeg() ?: lastDisplayRotDeg ?: 0
        lastDisplayRotDeg = displayDeg
        if (displayDeg != loggedDisplayRotDeg) {
            loggedDisplayRotDeg = displayDeg
            Timber.i("DISPLAY_ROT=%d", displayDeg)
        }
        val cls = StClass(rotCwDeg, mirror)
        lastStClassBySource[sourceId] = cls
        applyOrientation(sourceId, cls, displayDeg)
    }

    /** Display rotation in DEGREES (Display.getRotation() constants are 0..3, not degrees). */
    private fun readDisplayRotationDeg(): Int? {
        val activity = lifecycleOwner as? android.app.Activity ?: return null
        return try {
            @Suppress("DEPRECATION")
            val rotation = activity.windowManager.defaultDisplay.rotation
            rotation * 90
        } catch (t: Throwable) {
            Timber.w("DISPLAY_ROT_READ_FAILED %s", t.message)
            null
        }
    }

    /**
     * Round-25 mandate 2: display rotation re-read on every configuration
     * change; compensation re-runs from the LAST classified ST classes.
     * Logs DISPLAY_ROT=<deg> on every actual change. Wired from the
     * Activity's onConfigurationChanged.
     */
    fun onDisplayRotationMaybeChanged() {
        val deg = readDisplayRotationDeg() ?: return
        val previous = lastDisplayRotDeg
        lastDisplayRotDeg = deg
        if (deg != loggedDisplayRotDeg) {
            loggedDisplayRotDeg = deg
            Timber.i("DISPLAY_ROT=%d reason=config", deg)
        }
        if (previous == null || previous == deg) return
        for ((sourceId, cls) in lastStClassBySource.toList()) {
            applyOrientation(sourceId, cls, deg)
        }
    }

    /**
     * Applies the compensation for one source's ST class + display rotation
     * to its layer (Camera matches by layer id, Video by sourceId), logs
     * ORIENT_APPLY (with DISPLAY_ROT, per the round-25 contract) and commits
     * on change.
     */
    private fun applyOrientation(sourceId: String, cls: StClass, displayDeg: Int) {
        var applied: StCompensation? = null
        var changedAny = false
        scenes.value = scenes.value.map { s ->
            s.copy(layers = s.layers.map { l ->
                when {
                    l is LayerDefinition.Camera && l.id == sourceId -> {
                        val isFront = l.lensFacing == RenderLensFacing.FRONT
                        val comp = StOrientation.transformFromClass(cls, isFront, displayDeg)
                        applied = comp
                        lastOrientationComp[sourceId] = comp
                        if (l.transform.uvRotationDeg != comp.uvRotDeg || l.transform.mirrorX != comp.mirrorX) {
                            changedAny = true
                            l.copy(transform = l.transform.copy(uvRotationDeg = comp.uvRotDeg, mirrorX = comp.mirrorX))
                        } else l
                    }
                    l is LayerDefinition.Video && l.sourceId == sourceId -> {
                        val comp = StOrientation.transformFromClass(cls, isFront = false, displayRotation = displayDeg)
                        applied = comp
                        lastOrientationComp[l.id] = comp
                        if (l.transform.uvRotationDeg != comp.uvRotDeg || l.transform.mirrorX != comp.mirrorX) {
                            changedAny = true
                            l.copy(transform = l.transform.copy(uvRotationDeg = comp.uvRotDeg, mirrorX = comp.mirrorX))
                        } else l
                    }
                    else -> l
                }
            })
        }
        val comp = applied
        Timber.i(
            "ORIENT_APPLY id=%s DISPLAY_ROT=%d st_class=%s -> uvRot=%.0f mirrorX=%s changed=%s",
            sourceId, displayDeg, cls.label(),
            comp?.uvRotDeg ?: -1f, comp?.mirrorX?.toString() ?: "?", changedAny,
        )
        if (changedAny) commit()
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
        audioOutJob?.cancel()
        masterMonitor.release()
        engine.onExternalSourceReady = null
        engine.onExternalSourceReleased = null
        if (recorder.isBusy) recorder.stop()
        videoControllers.values.forEach { it.release() }
        videoControllers.clear()
        mic.stop()
        // Engine is app-scoped; it stays alive across configuration changes.
    }
}

/** A finished recording handed to the UI for the auto-share sheet. */
data class SavedRecording(val uri: Uri, val name: String)

private fun LayerDefinition.duplicateWithNewId(): LayerDefinition = when (this) {
    is LayerDefinition.Camera -> copy(id = id + "-copy")
    is LayerDefinition.Image -> this // sourceId shared is fine (bitmap already registered)
    is LayerDefinition.Video -> this
    is LayerDefinition.Text -> this
    is LayerDefinition.Color -> copy(id = id + "-copy")
}
