package com.vcamstudio.engine.render.render

import android.util.Log
import com.vcamstudio.core.clock.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The "never black" sentinel (blueprint §B.3): while a preview output is
 * attached, if no frame has been presented for [STALL_MS], force a controlled
 * output re-initialization on the render thread. The render thread keeps its
 * last scene FBO, so recovery re-presents a real frame — not a black one.
 */
class Watchdog(
    private val clock: Clock,
    private val check: () -> WatchdogStatus,
    private val onRecover: (String) -> Unit,
) {
    data class WatchdogStatus(
        val expectingFrames: Boolean,
        val lastPresentMonotonicMs: Long,
    )

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vcam-watchdog").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.scheduleWithFixedDelay(this::tick, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
    }

    private fun tick() {
        if (!running) return
        try {
            val status = check()
            if (!status.expectingFrames) return
            val last = status.lastPresentMonotonicMs
            if (last <= 0) return // never presented yet; startup grace handled by recovery logic
            val age = clock.nowMs() - last
            if (age > STALL_MS) {
                Log.w(TAG, "watchdog: no present for ${age}ms — forcing recovery")
                onRecover("watchdog: stall ${age}ms")
            }
        } catch (t: Throwable) {
            // Watchdog must never die.
            Log.e(TAG, "watchdog tick failed", t)
        }
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "vcam-watchdog"
        const val STALL_MS = 750L
        const val CHECK_MS = 250L
    }
}
