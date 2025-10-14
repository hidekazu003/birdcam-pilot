package io.mayu.birdpilot.detector

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

class DetectorGate(
    val minIntervalMs: Long
) {
    private val lastAcquireMs = AtomicLong(0L)

    fun tryAcquire(now: Long = SystemClock.elapsedRealtime()): Decision {
        while (true) {
            val previous = lastAcquireMs.get()
            val elapsed = if (previous == 0L) Long.MAX_VALUE else now - previous
            if (elapsed < minIntervalMs) {
                return Decision.Rejected(elapsedSinceLastMs = elapsed)
            }
            if (lastAcquireMs.compareAndSet(previous, now)) {
                return Decision.Accepted(elapsedSinceLastMs = if (previous == 0L) Long.MAX_VALUE else elapsed)
            }
        }
    }

    sealed class Decision {
        abstract val elapsedSinceLastMs: Long

        data class Accepted(override val elapsedSinceLastMs: Long) : Decision()
        data class Rejected(override val elapsedSinceLastMs: Long) : Decision()
    }
}
