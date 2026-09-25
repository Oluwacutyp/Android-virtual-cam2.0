package com.vcamstudio.app.di

import android.content.Context
import com.vcamstudio.core.clock.Clock
import com.vcamstudio.core.clock.SystemClockImpl
import com.vcamstudio.core.dispatch.DefaultDispatcherProvider
import com.vcamstudio.core.dispatch.DispatcherProvider
import com.vcamstudio.engine.audio.AudioMixer
import com.vcamstudio.engine.audio.MicLevelMonitor
import com.vcamstudio.engine.capture.CameraSource
import com.vcamstudio.engine.output.RecordingController
import com.vcamstudio.engine.render.render.RenderEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun clock(): Clock = SystemClockImpl()

    @Provides
    @Singleton
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    /** App-scoped: survives Activity recreation (never-black requirement). */
    @Provides
    @Singleton
    fun renderEngine(clock: Clock, dispatchers: DispatcherProvider): RenderEngine =
        RenderEngine(clock, dispatchers)

    @Provides
    @Singleton
    fun cameraSource(@ApplicationContext context: Context, dispatchers: DispatcherProvider): CameraSource =
        CameraSource(context, dispatchers)

    @Provides
    @Singleton
    fun appScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)

    @Provides
    @Singleton
    fun micLevelMonitor(scope: CoroutineScope): MicLevelMonitor = MicLevelMonitor(scope)

    @Provides
    @Singleton
    fun audioMixer(): AudioMixer = AudioMixer()

    @Provides
    @Singleton
    fun recordingController(): RecordingController = RecordingController()

    // ---- Phase 2: model manager + SCRFD detection ----
    @Provides
    @Singleton
    fun modelManager(@ApplicationContext context: Context): com.vcamstudio.engine.aicore.ModelManager =
        com.vcamstudio.engine.aicore.ModelManager(context)

    @Provides
    @Singleton
    fun faceDetectionController(): com.vcamstudio.engine.aiface.FaceDetectionController =
        com.vcamstudio.engine.aiface.FaceDetectionController()
}
