package com.vcamstudio.engine.render.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `bucket boundaries`() {
        assertEquals(0, DiagnosticsCollector.bucketIndex(8.0f))
        assertEquals(1, DiagnosticsCollector.bucketIndex(8.4f))
        assertEquals(1, DiagnosticsCollector.bucketIndex(16.6f))
        assertEquals(2, DiagnosticsCollector.bucketIndex(20f))
        assertEquals(3, DiagnosticsCollector.bucketIndex(33.0f))
        assertEquals(4, DiagnosticsCollector.bucketIndex(40f))
        assertEquals(5, DiagnosticsCollector.bucketIndex(60f))
        assertEquals(6, DiagnosticsCollector.bucketIndex(90f))
        assertEquals(7, DiagnosticsCollector.bucketIndex(250f))
    }

    @Test
    fun `snapshot percentiles`() {
        val collector = DiagnosticsCollector()
        // Simulate 20 frames at ~16.7ms and one stall at 120ms.
        repeat(20) { collector.onPresented(16.7f, 0) }
        collector.onPresented(120f, 2)
        val snap = collector.snapshot(nowMs = 1000L, health = EngineHealth.HEALTHY, lastPresentMonotonicMs = 900L)
        assertEquals(21, snap.presentedFrames.toInt())
        assertEquals(2, snap.droppedFrames.toInt())
        assertEquals(16.7f, snap.p50Ms, 0.01f)
        assertEquals(120f, snap.maxMs, 0.01f)
        assertEquals(1, snap.histogram[DiagnosticsCollector.bucketIndex(120f)])
    }
}
