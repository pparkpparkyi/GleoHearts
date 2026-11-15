package com.example.gleo_type.realtime

import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.core.MusicPayload

data class RealtimeSession(val runId: String, val driveSessionId: String) {
    init { require(runId.isNotBlank() && driveSessionId.isNotBlank()) }
}

enum class ObservationKind { VEHICLE, SPEED, MUSIC, START, STOP, OTHER }

data class RealtimeObservation(
    val session: RealtimeSession,
    val kind: ObservationKind,
    val observedMs: Long?,
    val receivedAt: String? = null,
    val eventId: String? = null,
    val speedKmh: Double? = null,
    val playbackSessionId: String? = null,
    val label: String? = null,
    val musicId: String? = null,
    val transitionReason: String? = null,
    val sourceSampleId: String? = null,
    val sourceObservedMs: Long? = null,
) {
    companion object {
        fun fromEvent(runId: String, event: EventDto): RealtimeObservation? {
            val drive = event.driveSessionId?.takeIf(String::isNotBlank) ?: return null
            if (runId.isBlank()) return null
            val kind = when (event.domain.name) {
                "VEHICLE" -> ObservationKind.VEHICLE
                "MUSIC" -> ObservationKind.MUSIC
                else -> ObservationKind.OTHER
            }
            return RealtimeObservation(RealtimeSession(runId, drive), kind,
                event.observation?.receivedElapsedMs, event.observation?.receivedAt,
                event.eventId, playbackSessionId = event.observation?.playbackSessionId,
                label = event.label.code, musicId = (event.payload as? MusicPayload)?.music?.musicId,
                transitionReason = event.observation?.transitionReason)
        }
    }
}

data class BufferedObservation(val arrivalOrder: Long, val value: RealtimeObservation)
enum class OfferResult { ACCEPTED, DUPLICATE, WRONG_SESSION, INVALID, TOO_OLD, CLOSED, CLOCK_REGRESSION }
enum class BufferState { OPEN, STOPPED, IDLE_EXPIRED, DISPOSED }

data class BufferDiagnostics(
    val accepted: Long, val rejected: Long, val duplicates: Long, val reversals: Long,
    val expired: Long, val sampleOverflow: Long, val boundaryOverflow: Long, val boundaryRejected: Long,
    val upstreamMissing: Long, val missingObservedTime: Long,
    val missingStart: Boolean, val missingStop: Boolean,
    val speedCapacityEvicted: Long = 0,
)

data class RealtimeSnapshot(val session: RealtimeSession, val entries: List<BufferedObservation>,
                            val state: BufferState, val diagnostics: BufferDiagnostics)

/** One instance per run/drive pair. The supplied clock shares the observation clock domain. */
class RealtimeEventBuffer(
    private val session: RealtimeSession,
    private val clockMs: () -> Long,
    private val sampleCapacity: Int = 256,
    private val boundaryCapacity: Int = 64,
    private val retentionMs: Long = 120_000,
    private val idleTimeoutMs: Long = 300_000,
) : AutoCloseable {
    init { require(sampleCapacity > 0 && boundaryCapacity > 0 && retentionMs > 0 && idleTimeoutMs > 0) }

    private data class Entry(val item: BufferedObservation, val arrivalMs: Long)
    private val samples = ArrayDeque<Entry>()
    private val boundaries = ArrayDeque<Entry>()
    private val ids = HashSet<String>()
    private var state = BufferState.OPEN
    private var lastClock = 0L
    private var lastArrival: Long? = null
    private var latestObserved = -1L
    private var started = false
    private var stopped = false
    private var accepted = 0L
    private var rejected = 0L
    private var duplicates = 0L
    private var reversals = 0L
    private var expired = 0L
    private var sampleOverflow = 0L
    private var boundaryOverflow = 0L
    private var boundaryRejected = 0L
    private var speedCapacityEvicted = 0L
    private var upstreamMissing = 0L
    private var missingTime = 0L

    @Synchronized fun offer(value: RealtimeObservation): OfferResult {
        val boundary = value.kind == ObservationKind.START || value.kind == ObservationKind.STOP ||
            value.kind == ObservationKind.MUSIC && value.label != "PLAYBACK_STATE_CHANGED"
        fun rejectInput(result: OfferResult): OfferResult {
            if (boundary && result != OfferResult.DUPLICATE) boundaryRejected++
            return reject(result)
        }
        val now = clockMs()
        if (now < lastClock) return rejectInput(OfferResult.CLOCK_REGRESSION)
        maintain(now)
        if (value.session != session) return reject(OfferResult.WRONG_SESSION)
        if (state != BufferState.OPEN) return rejectInput(OfferResult.CLOSED)
        val time = value.observedMs
        if (time != null && (time < 0 || time > now) || value.eventId?.isBlank() == true ||
            value.kind == ObservationKind.SPEED && (time == null || value.eventId != null ||
                value.speedKmh?.let { kotlin.math.abs(it) <= Float.MAX_VALUE.toDouble() * 3.6 } != true)) return rejectInput(OfferResult.INVALID)
        if (value.eventId != null && value.eventId in ids) {
            duplicates++
            return reject(OfferResult.DUPLICATE)
        }
        if (value.kind == ObservationKind.START && started) {
            duplicates++
            return reject(OfferResult.DUPLICATE)
        }
        if (time != null && time < latestObserved) reversals++
        if (time != null && now - time > retentionMs && value.kind != ObservationKind.STOP) {
            expired++
            return rejectInput(OfferResult.TOO_OLD)
        }
        if (time == null) missingTime++ else latestObserved = maxOf(time, latestObserved)
        val queue = if (boundary) boundaries else samples
        if (queue.size == if (boundary) boundaryCapacity else sampleCapacity) {
            // Speed samples retain a time window even under frequent non-speed updates.
            val victim = if (boundary) queue.first() else
                queue.firstOrNull { it.item.value.kind != ObservationKind.SPEED } ?: queue.first()
            queue.remove(victim)
            victim.item.value.eventId?.let(ids::remove)
            if (victim.item.value.kind == ObservationKind.SPEED) speedCapacityEvicted++
            if (boundary) boundaryOverflow++ else sampleOverflow++
        }
        value.eventId?.let(ids::add)
        queue.addLast(Entry(BufferedObservation(++accepted, value), now))
        lastArrival = now
        if (value.kind == ObservationKind.START) started = true
        if (value.kind == ObservationKind.STOP) {
            stopped = true
            state = BufferState.STOPPED
        }
        return OfferResult.ACCEPTED
    }

    /** Call only for loss reported by the producer. Sequence continuity cannot establish completeness. */
    @Synchronized fun reportUpstreamMissing() { upstreamMissing++ }

    @Synchronized fun hasIntegrityLoss(): Boolean = reversals > 0 || sampleOverflow > 0 ||
        boundaryOverflow > 0 || boundaryRejected > 0 || upstreamMissing > 0 || missingTime > 0

    @Synchronized fun snapshot(): RealtimeSnapshot {
        maintain(maxOf(lastClock, clockMs()))
        return RealtimeSnapshot(session, (samples + boundaries).map { it.item }.sortedBy { it.arrivalOrder },
            state, BufferDiagnostics(accepted, rejected, duplicates, reversals, expired, sampleOverflow,
                boundaryOverflow, boundaryRejected, upstreamMissing, missingTime, !started, !stopped,
                speedCapacityEvicted))
    }

    private fun reject(result: OfferResult): OfferResult {
        rejected++
        return result
    }

    private fun maintain(now: Long) {
        lastClock = now
        for (queue in listOf(samples, boundaries)) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - (entry.item.value.observedMs ?: entry.arrivalMs) > retentionMs) {
                    iterator.remove()
                    entry.item.value.eventId?.let(ids::remove)
                    expired++
                }
            }
        }
        if (state == BufferState.OPEN && lastArrival?.let { now - it >= idleTimeoutMs } == true) {
            state = BufferState.IDLE_EXPIRED
            expired += samples.size + boundaries.size
            clearEntries()
        }
    }

    private fun clearEntries() {
        samples.clear()
        boundaries.clear()
        ids.clear()
    }

    @Synchronized override fun close() {
        clearEntries()
        state = BufferState.DISPOSED
    }
}
