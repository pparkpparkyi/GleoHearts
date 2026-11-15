package com.example.gleo_type.event.vehicle

/** Received samples at most once per second, including unchanged speed. No cached repeats. */
class SpeedSampleRecorder(private val write: (Float, Long, Long?) -> Unit) {
    private var lastAt: Long? = null
    @Synchronized fun accept(mps: Float, receivedElapsedMs: Long, vehicleTimestampNanos: Long?) {
        if (!mps.isFinite()) return
        val last = lastAt
        if (last != null && receivedElapsedMs - last < 1_000) return
        lastAt = receivedElapsedMs
        write(mps, receivedElapsedMs, vehicleTimestampNanos)
    }
    @Synchronized fun reset() { lastAt = null }
}
