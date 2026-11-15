package com.example.gleo_type.event.vehicle

/** Same-boot AAOS timestamp validation, not clock-offset estimation. Raw values are never rewritten. */
data class SpeedTimeEvidence(val sampleId: String, val status: String, val sourceElapsedMs: Long?,
    val sourceAgeMs: Long?, val receivedElapsedMs: Long, val eligible: Boolean) {
    fun fields(): Map<String, Any?> = mapOf(
        "sourceSampleId" to sampleId, "sourceTimeQuality" to status,
        "normalizedSourceElapsedMs" to sourceElapsedMs?.takeIf { eligible }?.toString(),
        "normalizedObservationElapsedMs" to receivedElapsedMs.takeIf { eligible }?.toString(),
        "sourceAgeAtCallbackMs" to sourceAgeMs?.toString(), "sourceTimeEligible" to eligible.toString(),
        "normalizationVersion" to "aaos_same_boot_ns_to_ms_v1",
        "normalizationRule" to "integer_ns_div_1000000_no_offset_estimation",
        "normalizationRoundingBoundMs" to "1", "clockSynchronizationUncertaintyMs" to null,
        "observationClock" to "app_callback_elapsed_realtime")
}

class SpeedTimestampValidator(private val maxAgeMs: Long = 2500, private val futureToleranceMs: Long = 1) {
    init { require(maxAgeMs >= 0 && futureToleranceMs in 0..1) }
    private var lastSourceNanos: Long? = null
    private var lastReceivedMs: Long? = null
    private val rejectedFuture = linkedSetOf<Long>() // bounded tombstones: cached bad events cannot age into validity
    @Volatile var latest: SpeedTimeEvidence? = null
        private set
    @Synchronized fun observe(sampleId: String, rawSourceNanos: Long?, receivedMs: Long): SpeedTimeEvidence {
        val source = rawSourceNanos?.takeIf { it > 0 }?.div(1_000_000)
        val age = source?.let { receivedMs - it }
        val status = when {
            receivedMs < 0 || lastReceivedMs?.let { receivedMs < it } == true -> "RECEIVE_CLOCK_REVERSED"
            rawSourceNanos == null || rawSourceNanos == 0L -> "UNKNOWN_SOURCE_TIMESTAMP"
            rawSourceNanos < 0 -> "INVALID_SOURCE_TIMESTAMP"
            rawSourceNanos in rejectedFuture -> "KNOWN_INVALID_SOURCE_TIMESTAMP"
            age!! < -futureToleranceMs -> "FUTURE_SOURCE_TIMESTAMP"
            age > maxAgeMs -> "STALE_SOURCE_TIMESTAMP"
            lastSourceNanos == rawSourceNanos -> "DUPLICATE_SOURCE_TIMESTAMP"
            lastSourceNanos?.let { rawSourceNanos < it } == true -> "REVERSED_SOURCE_TIMESTAMP"
            age < 0 -> "VALID_WITH_1MS_TOLERANCE"
            else -> "VALID"
        }
        val eligible = status == "VALID" || status == "VALID_WITH_1MS_TOLERANCE"
        if (status == "FUTURE_SOURCE_TIMESTAMP") {
            rejectedFuture.add(rawSourceNanos!!)
            if (rejectedFuture.size > 256) rejectedFuture.remove(rejectedFuture.first())
        }
        if (eligible) { lastSourceNanos = rawSourceNanos; lastReceivedMs = receivedMs }
        return SpeedTimeEvidence(sampleId, status, source, age, receivedMs, eligible).also { latest = it }
    }
    fun blockingReason(nowMs: Long? = null): String? {
        val e = latest ?: return null
        if (!e.eligible) return "sensor_${e.status.lowercase()}"
        if (nowMs != null) {
            if (nowMs < e.receivedElapsedMs) return "sensor_receive_clock_reversed"
            if (nowMs - e.sourceElapsedMs!! > maxAgeMs) return "sensor_stale_source_timestamp"
        }
        return null
    }
    @Synchronized fun reset() { latest = null; lastSourceNanos = null; lastReceivedMs = null; rejectedFuture.clear() }
}
