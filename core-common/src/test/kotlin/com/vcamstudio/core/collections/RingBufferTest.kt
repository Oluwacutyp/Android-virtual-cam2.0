package com.vcamstudio.core.collections

import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferTest {

    @Test
    fun `evicts oldest when capacity exceeded`() {
        val rb = RingBuffer<Int>(3)
        rb.add(1); rb.add(2); rb.add(3); rb.add(4)
        assertEquals(listOf(2, 3, 4), rb.snapshot())
        assertEquals(3, rb.size)
    }

    @Test
    fun `snapshot is a copy`() {
        val rb = RingBuffer<String>(2)
        rb.add("a")
        val snap = rb.snapshot()
        rb.add("b"); rb.add("c")
        assertEquals(listOf("a"), snap)
    }

    @Test
    fun `clear empties`() {
        val rb = RingBuffer<Int>(2)
        rb.add(1)
        rb.clear()
        assertEquals(0, rb.size)
    }
}
