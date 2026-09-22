package com.vcamstudio.core.clock

import android.os.SystemClock

/**
 * Injectable monotonic clock. Engines use this instead of calling
 * [SystemClock] directly so tests can drive virtual time.
 */
interface Clock {
    /** Monotonic milliseconds (elapsedRealtime). */
    fun nowMs(): Long

    /** Monotonic nanoseconds (elapsedRealtimeNanos). */
    fun nowNanos(): Long
}

class SystemClockImpl : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
