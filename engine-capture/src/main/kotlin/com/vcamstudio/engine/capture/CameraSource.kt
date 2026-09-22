package com.vcamstudio.engine.capture

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.vcamstudio.core.dispatch.DispatcherProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX wrapper that feeds camera frames into an engine-owned external
 * texture surface (the engine hands the Surface to [bind]).
 *
 * Pro controls: exposure compensation / zoom / torch / tap-to-focus apply at
 * runtime; ISO / WB / AF program / fixed FPS are applied at bind time through
 * Camera2Interop (changing them rebinds — fast, sub-second on modern devices).
 */
class CameraSource(
    private val context: Context,
    dispatchers: DispatcherProvider,
) {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Bound(
            val lensFacing: LensFacing,
            /** Exposure compensation range in EV steps (index space). */
            val exposureIndexRange: IntRange?,
            /** Whether the device reports a flash unit (torch availability). */
            val hasFlash: Boolean,
        ) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mainScope = CoroutineScope(SupervisorJob() + dispatchers.main)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundControls: ProControls? = null

    /**
     * Binds the camera to [surface] (engine external texture). Must be called
     * from the main thread context (suspends internally on Main).
     */
    fun bind(surface: Surface, controls: ProControls, lifecycleOwner: LifecycleOwner) {
        mainScope.launch {
            try {
                _state.value = State.Starting
                val cameraProvider = provider ?: ProcessCameraProvider
                    .getInstance(context).await()
                    .also { provider = it }

                val previewBuilder = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(controls.targetWidth, controls.targetHeight),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )

                val extender = Camera2Interop.Extender(previewBuilder)
                controls.iso?.let { extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    controls.whiteBalance.awbCode,
                )
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    controls.focusMode.afCode,
                )
                controls.fpsRange?.let { range ->
                    extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(range.first, range.last),
                    )
                }

                val preview = previewBuilder.build()
                preview.setSurfaceProvider { request: SurfaceRequest ->
                    request.provideSurface(
                        surface,
                        androidx.core.content.ContextCompat.getMainExecutor(context),
                    ) { result ->
                        // The Surface is engine-owned; CameraX merely stops writing.
                        result.getSurface() // no-op touch to acknowledge
                    }
                }

                cameraProvider.unbindAll()
                val selector = if (controls.lensFacing == LensFacing.FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
                camera = cam
                boundControls = controls

                val exposureState = cam.cameraInfo.exposureState
                val range = exposureState.exposureCompensationRange.let {
                    IntRange(it.lower, it.upper)
                }
                applyRuntime(controls)
                _state.value = State.Bound(
                    lensFacing = controls.lensFacing,
                    exposureIndexRange = range,
                    hasFlash = cam.cameraInfo.hasFlashUnit(),
                )
                Log.i(TAG, "camera bound lens=${controls.lensFacing}")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                _state.value = State.Failed(t.message ?: "camera bind failed")
            }
        }
    }

    /** Applies runtime-tunable controls (zoom, EV, torch). */
    fun applyRuntime(controls: ProControls) {
        val cam = camera ?: return
        controls.zoomRatio.takeIf { it != 1f }?.let { cam.cameraControl.setZoomRatio(it) }
        controls.torch.takeIf { cam.cameraInfo.hasFlashUnit() }?.let { cam.cameraControl.enableTorch(it) }
        controls.exposureCompensationIndex?.let { idx ->
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            cam.cameraControl.setExposureCompensation(idx.coerceIn(range.lower, range.upper))
        }
    }

    /** Focus + meter at view coordinates (x, y) within a (viewW x viewH) stage. */
    fun tapToFocus(x: Float, y: Float, viewW: Float, viewH: Float) {
        val cam = camera ?: return
        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(viewW, viewH)
        val point = factory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            ).setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build(),
        )
    }

    fun unbind() {
        mainScope.launch {
            try {
                provider?.unbindAll()
            } catch (t: Throwable) {
                Log.w(TAG, "unbind issue", t)
            }
            camera = null
            boundControls = null
            _state.value = State.Idle
        }
    }

    fun currentlyBound(): ProControls? = boundControls

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        cont.resume(get())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                Executor { it.run() },
            )
            cont.invokeOnCancellation { cancel(false) }
        }

    private fun interface Executor : java.util.concurrent.Executor

    companion object {
        private const val TAG = "vcam-camera"
    }
}
