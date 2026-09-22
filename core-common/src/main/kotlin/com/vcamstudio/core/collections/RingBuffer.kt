package com.vcamstudio.core.collections

/**
 * Fixed-capacity FIFO ring buffer for diagnostics history
 * (frame times, recovery events). Thread-safe.
 */
class RingBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val buf = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast(item)
    }

    @Synchronized
    fun snapshot(): List<T> = ArrayList(buf)

    @Synchronized
    fun clear() = buf.clear()

    val size: Int
        @Synchronized get() = buf.size
}
