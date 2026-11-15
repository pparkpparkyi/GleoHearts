package com.example.gleo_type.realtime

import kotlin.math.sqrt
import kotlin.math.abs

enum class ContextQuality { AVAILABLE, INSUFFICIENT_DATA }
enum class SampleFreshness { FRESH, STALE, UNAVAILABLE }

/** Existing ListeningTracker credit, not a second listening-time estimator. */
data class DrivingCreditWindow(val fromMs: Long, val toMs: Long, val creditedWallMs: Double)

data class DrivingContext(
    val session: RealtimeSession,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val averageSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val speedStdDevKmh: Double?,
    val speedChangeKmh: Double?,
    val stationaryRatio: Double?,
    val sampleCount: Int,
    val observedDurationMs: Long,
    val coverage: Double,
    val lastReceivedAt: String?,
    val latestSampleAgeMs: Long?,
    val freshness: SampleFreshness,
    val quality: ContextQuality,
    val qualityFlags: Set<String>,
    val brakeActivations: Int? = null,
    val steeringAngle: Double? = null,
    val steeringChange: Double? = null,
    val cruiseActive: Boolean? = null,
)

class DrivingContextProcessor(
    private val windowMs: Long = 10_000,
    private val maxGapMs: Long = 2_500,
    private val minimumCoverage: Double = 0.6,
    private val stationaryThresholdKmh: Double = 0.5,
) {
    init {
        require(windowMs > 0 && maxGapMs > 0 && minimumCoverage in 0.0..1.0 &&
            stationaryThresholdKmh.isFinite() && stationaryThresholdKmh >= 0)
    }

    fun process(snapshot: RealtimeSnapshot, atMs: Long,
                creditWindows: List<DrivingCreditWindow>? = null): DrivingContext {
        require(atMs >= 0)
        val start = (atMs - windowMs).coerceAtLeast(0)
        val samples = snapshot.entries.map { it.value }.filter {
            it.kind == ObservationKind.SPEED && it.observedMs!! <= atMs
        }.sortedBy { it.observedMs }
        val inside = samples.filter { it.observedMs!! >= start }
        val windows = creditWindows?.filter { it.toMs > it.fromMs && it.creditedWallMs > 0 }
        require(windows == null || windows.all { it.creditedWallMs.isFinite() &&
            it.creditedWallMs <= it.toMs - it.fromMs + 0.001 && it.fromMs >= start && it.toMs <= atMs } &&
            windows.zipWithNext().all { (a, b) -> a.toMs <= b.fromMs })
        var windowIndex = 0
        var duration = 0.0
        var stationary = 0.0
        var mean = 0.0
        var moment = 0.0
        val flags = linkedSetOf("brake_unavailable", "steering_unavailable", "cruise_unavailable")
        for ((previous, next) in samples.zipWithNext()) {
            val left = previous.observedMs!!
            val right = next.observedMs!!
            if (right <= start) continue
            val gap = right - left
            if (gap > maxGapMs) {
                flags += "observation_gap"
                continue
            }
            val clippedLeft = maxOf(left, start)
            val weight = if (windows == null) (right - clippedLeft).toDouble() else {
                while (windowIndex < windows.size && windows[windowIndex].toMs <= clippedLeft) windowIndex++
                var index = windowIndex
                var credit = 0.0
                while (index < windows.size && windows[index].fromMs < right) {
                    val w = windows[index++]
                    val overlap = (minOf(right, w.toMs) - maxOf(clippedLeft, w.fromMs)).coerceAtLeast(0)
                    credit += overlap * w.creditedWallMs / (w.toMs - w.fromMs)
                }
                credit
            }
            if (weight <= 0) continue
            val speed = abs(previous.speedKmh!!)
            duration += weight
            val delta = speed - mean
            mean += delta * (weight.toDouble() / duration)
            moment += weight * delta * (speed - mean)
            if (speed <= stationaryThresholdKmh) stationary += weight
        }
        val d = snapshot.diagnostics
        if (d.reversals > 0) flags += "observation_reversal"
        if (d.boundaryOverflow > 0) flags += "boundary_overflow"
        if (d.boundaryRejected > 0) flags += "boundary_rejected"
        if (d.sampleOverflow > 0) flags += "sample_overflow"
        if (d.upstreamMissing > 0) flags += "upstream_loss"
        if (d.missingStart) flags += "missing_start"
        if (d.missingStop && snapshot.state != BufferState.OPEN) flags += "missing_stop"
        val last = samples.lastOrNull()
        val age = last?.observedMs?.let { atMs - it }
        val freshness = when {
            age == null -> SampleFreshness.UNAVAILABLE
            age <= maxGapMs -> SampleFreshness.FRESH
            else -> SampleFreshness.STALE
        }
        val denominator = windows?.sumOf { it.creditedWallMs } ?: windowMs.toDouble()
        val coverage = if (denominator > 0) duration / denominator else 0.0
        val quality = if (duration > 0 && coverage >= minimumCoverage && freshness == SampleFreshness.FRESH)
            ContextQuality.AVAILABLE else ContextQuality.INSUFFICIENT_DATA
        return DrivingContext(snapshot.session, start, atMs, mean.takeIf { duration > 0 },
            inside.maxOfOrNull { abs(it.speedKmh!!) },
            if (duration > 0) sqrt((moment / duration).coerceAtLeast(0.0)) else null,
            if (inside.size >= 2) abs(inside.last().speedKmh!!) - abs(inside.first().speedKmh!!) else null,
            if (duration > 0) stationary.toDouble() / duration else null,
            inside.size, duration.toLong(), coverage, last?.receivedAt, age, freshness, quality, flags)
    }
}
