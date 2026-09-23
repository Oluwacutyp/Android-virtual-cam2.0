package com.vcamstudio.engine.render.render

import com.vcamstudio.core.collections.RingBuffer
import com.vcamstudio.core.model.Size

/** Liveness of the render pipeline, surfaced in the Diagnostics UI. */
enum class EngineHealth { HEALTHY, DEGRADED, UNHEALTHY }

data class RecoveryEvent(val atMs: Long, val reason: String)

/**
 * Immutable diagnostics snapshot published at 1 Hz. This is the data behind
 * the "never black" guarantee: frame-time histogram, drop counters, recovery
 * log, and last-presented age.
 */
data class DiagnosticsSnapshot(
    val fps: Float = 0f,
    val presentedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val p50Ms: Float = 0f,
    val p95Ms: Float = 0f,
    val maxMs: Float = 0f,
    /** Frame counts per bucket (see [DiagnosticsCollector.bucketIndex]). */
    val histogram: List<Int> = List(HISTOGRAM_BUCKETS) { 0 },
    val health: EngineHealth = EngineHealth.HEALTHY,
    val recoveries: List<RecoveryEvent> = emptyList(),
    val glRenderer: String = "",
    val glVersion: String = "",
    val eglApi: String = "",
    val sceneSize: Size? = null,
    val previewSize: Size? = null,
    val externalSourceCount: Int = 0,
    /** ms since the last successful present; -1 = never presented. */
    val lastPresentAgeMs: Long = -1,
    /** Non-null when engine init failed (EGL/shader) — surfaced in UI + dump. */
    val initError: String? = null,
) {
    companion object {
        const val HISTOGRAM_BUCKETS = 8
    }
}

/** Thread-safe collector updated by the render thread, read by the facade. */
class DiagnosticsCollector {

    private val frameTimesMs = RingBuffer<Float>(512)
    private val recoveries = RingBuffer<RecoveryEvent>(32)
    private val histogram = IntArray(DiagnosticsSnapshot.HISTOGRAM_BUCKETS)

    @Volatile var presentedFrames: Long = 0
        private set
    @Volatile var droppedFrames: Long = 0
        private set
    @Volatile var lastPresentUptimeMs: Long = 0
        private set
    @Volatile var glRenderer: String = ""
    @Volatile var glVersion: String = ""
    @Volatile var eglApi: String = ""
    @Volatile var sceneSize: Size? = null
    @Volatile var previewSize: Size? = null
    @Volatile var externalSourceCount: Int = 0
    @Volatile var initError: String? = null

    /**
     * Histogram bucket for a frame time.
     * 0: <=8.33 (120fps) · 1: <=16.67 (60fps) · 2: <=25 · 3: <=33.3 (30fps)
     * 4: <=50 · 5: <=66.7 (15fps) · 6: <=100 · 7: >100 (stall)
     */
    fun onPresented(frameIntervalMs: Float, droppedThisFrame: Int) {
        frameTimesMs.add(frameIntervalMs)
        histogram[bucketIndex(frameIntervalMs)]++
        presentedFrames++
        if (droppedThisFrame > 0) droppedFrames += droppedThisFrame
        lastPresentUptimeMs = System.currentTimeMillis()
    }

    fun addRecovery(event: RecoveryEvent) = recoveries.add(event)

    /** Number of recovery events within the last [withinMs]. */
    fun recentRecoveryCount(nowMs: Long, withinMs: Long): Int =
        recoveries.snapshot().count { nowMs - it.atMs <= withinMs }

    fun snapshot(nowMs: Long, health: EngineHealth, lastPresentMonotonicMs: Long): DiagnosticsSnapshot {
        val times = frameTimesMs.snapshot()
        val sorted = times.sorted()
        val p50 = percentile(sorted, 0.50f)
        val p95 = percentile(sorted, 0.95f)
        val max = sorted.lastOrNull() ?: 0f
        val recent = recoveries.snapshot()
        return DiagnosticsSnapshot(
            fps = if (p50 > 0.5f) 1000f / p50 else 0f,
            presentedFrames = presentedFrames,
            droppedFrames = droppedFrames,
            p50Ms = p50,
            p95Ms = p95,
            maxMs = max,
            histogram = histogram.toList(),
            health = health,
            recoveries = recent,
            glRenderer = glRenderer,
            glVersion = glVersion,
            eglApi = eglApi,
            sceneSize = sceneSize,
            previewSize = previewSize,
            externalSourceCount = externalSourceCount,
            lastPresentAgeMs = if (lastPresentMonotonicMs <= 0) -1 else nowMs - lastPresentMonotonicMs,
            initError = initError,
        )
    }

    private fun percentile(sorted: List<Float>, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        private val BOUNDS = floatArrayOf(8.33f, 16.67f, 25f, 33.3f, 50f, 66.7f, 100f)

        fun bucketIndex(frameMs: Float): Int {
            var i = 0
            while (i < BOUNDS.size && frameMs > BOUNDS[i]) i++
            return i
        }
    }
}
