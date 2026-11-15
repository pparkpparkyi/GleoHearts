package com.example.gleo_type.recommendation

import com.example.gleo_type.realtime.*
import com.example.gleo_type.vehicle.CarConnectionSnapshot
import com.example.gleo_type.vehicle.CarConnectionState

enum class ContextInputSource { LIVE_SDK, REPLAY }
data class DrivingRecommendationInput(
    val runId: String, val driveSessionId: String?, val asOfElapsedMs: Long,
    val connectionToken: Long?, val quality: String, val fallbackReason: String?,
    val averageSpeedKmh: Double? = null, val stopped: Boolean? = null,
    val lowSpeed: Boolean? = null, val motionState: String? = null,
    val observedDurationMs: Long = 0, val coverage: Double = 0.0,
    val latestObservedElapsedMs: Long? = null, val latestReceivedAt: String? = null,
    val source: ContextInputSource = ContextInputSource.LIVE_SDK, val environment: String = "unverified",
    val sourceSampleIds: List<String> = emptyList(),
)

/** Read-only, bounded snapshot calculation. Never writes samples or changes a collection/session. */
class DrivingContextProvider(private val runId: String, private val drive: () -> String?,
    private val pipeline: RealtimePipeline, private val clock: () -> Long,
    private val source: ContextInputSource = ContextInputSource.LIVE_SDK,
    private val environment: String = "unverified", private val sensorTimeBlock: () -> String? = { null }) {
    private data class Transport(val owner: String, val state: CarConnectionSnapshot,
        val connectedAt: Long, val drive: String?, val revision: Long)
    @Volatile private var transport: Transport? = null
    @Synchronized fun attach(owner: String) {
        transport = Transport(owner, CarConnectionSnapshot(), -1, null, (transport?.revision ?: 0)+1)
    }
    @Synchronized fun connection(owner: String, state: CarConnectionSnapshot) {
        val old = transport ?: return
        if (old.owner != owner) return
        val connected = state.state == CarConnectionState.CONNECTED
        val same = connected && old.state.state == state.state && old.state.token == state.token
        transport = Transport(owner, state, if (same) old.connectedAt else if (connected) clock() else -1,
            if (same) old.drive else if (connected) drive() else null, old.revision+1)
    }
    fun current(expectedDrive: String?): DrivingRecommendationInput {
        val t = transport
        fun unknown(reason: String) = DrivingRecommendationInput(runId, expectedDrive, clock(), t?.state?.token,
            "unknown", reason, source=source, environment=environment)
        if (expectedDrive == null || expectedDrive != drive()) return unknown("inactive_or_wrong_drive")
        if (t == null || t.state.state != CarConnectionState.CONNECTED) return unknown("transport_unavailable")
        if (t.drive != expectedDrive || t.connectedAt < 0) return unknown("connection_session_mismatch")
        sensorTimeBlock()?.let { return unknown(it) }
        val usable = pipeline.usableContext()
        val snapshot = pipeline.snapshot() ?: return unknown("context_unknown")
        val now = clock()
        if (snapshot.session != RealtimeSession(runId,expectedDrive)) return unknown("run_or_drive_mismatch")
        if (snapshot.diagnostics.missingObservedTime > 0 || snapshot.entries.any {
                it.value.observedMs?.let { ms -> ms < 0 || ms > now } == true }) return unknown("untrusted_timestamp")
        val entries = snapshot.entries.filter { it.value.kind != ObservationKind.SPEED ||
            ((it.value.observedMs ?: -1) > t.connectedAt &&
                (it.value.sourceObservedMs?.let { ms -> ms > t.connectedAt } ?: true)) }
        val latest = entries.filter { it.value.kind==ObservationKind.SPEED }.maxOfOrNull { it.value.observedMs ?: -1 }
            ?: return unknown("speed_unavailable_after_connection")
        if (now-latest !in 0..2500) return unknown("stale_speed")
        if (usable == null) return unknown("context_unknown")
        val c = DrivingContextProcessor().process(snapshot.copy(entries=entries),now)
        if (c.quality != ContextQuality.AVAILABLE || c.freshness != SampleFreshness.FRESH || c.coverage < .6 ||
            c.observedDurationMs < 6000) return unknown("insufficient_post_connection_observation")
        val speed = c.averageSpeedKmh?.takeIf { it.isFinite() && it >= 0 } ?: return unknown("speed_unavailable")
        val still = transport
        if (still != t || expectedDrive != drive()) return unknown("connection_changed_during_read")
        sensorTimeBlock()?.let { return unknown(it) }
        val stopped = (c.stationaryRatio ?: 0.0) >= .8 && speed <= .5
        val low = !stopped && speed <= 20.0
        val samples = entries.map { it.value }.filter { it.kind == ObservationKind.SPEED && it.observedMs!! <= now }
            .sortedBy { it.observedMs }
        val inside = samples.filter { it.observedMs!! >= c.windowStartMs }
        val left = samples.lastOrNull { it.observedMs!! < c.windowStartMs }?.takeIf { previous ->
            inside.firstOrNull()?.observedMs?.let { it > c.windowStartMs && it - previous.observedMs!! <= 2500 } == true }
        val evidence = (inside + listOfNotNull(left))
            .sortedBy { it.observedMs }.mapNotNull { it.sourceSampleId }.distinct()
        return DrivingRecommendationInput(runId,expectedDrive,now,t.state.token,"available",null,
            speed,stopped,low,if(stopped)"stopped" else if(low)"low_speed" else "moving",
            c.observedDurationMs,c.coverage,latest,c.lastReceivedAt,source,environment,evidence)
    }
}
