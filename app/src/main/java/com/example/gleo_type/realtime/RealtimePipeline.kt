package com.example.gleo_type.realtime

import com.example.gleo_type.event.core.EventDto
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RealtimeIngressDiagnostics(
    val offered: Long, val accepted: Long, val speedAccepted: Long,
    val inactiveOrMismatched: Long, val contentionDropped: Long, val failures: Long,
)

/** Callback inputs never wait for the lock. Context calculation runs on the consumer's thread. */
class RealtimePipeline(
    val runId: String,
    private val clockMs: () -> Long,
    private val sampleCapacity: Int = 256,
    private val boundaryCapacity: Int = 64,
    private val retentionMs: Long = 120_000,
) {
    /** Optional bounded handoff; exceptions never affect original event storage or playback. */
    @Volatile var observationSink: ((RealtimeObservation, Boolean) -> Unit)? = null
    private val sessionLoss = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun notifySink(value: RealtimeObservation) {
        if (active?.hasIntegrityLoss() == true) sessionLoss.set(true)
        try { observationSink?.invoke(value, sessionLoss.get() || active?.hasIntegrityLoss() == true) }
        catch (_: Exception) { failures.incrementAndGet(); sessionLoss.set(true) }
    }
    private val lock = ReentrantLock()
    private var active: RealtimeEventBuffer? = null
    private var session: RealtimeSession? = null
    private var lastClosed: BufferDiagnostics? = null
    private val offered = AtomicLong()
    private val accepted = AtomicLong()
    private val speedAccepted = AtomicLong()
    private val inactive = AtomicLong()
    private val contention = AtomicLong()
    private val failures = AtomicLong()
    private val lastLossMs = AtomicLong(-1)

    fun start(driveId: String) = lock.withLock {
        if (session?.driveSessionId == driveId) return@withLock
        active?.let { lastClosed = it.snapshot().diagnostics; it.close() }
        val key = RealtimeSession(runId, driveId)
        session = key
        sessionLoss.set(false)
        active = RealtimeEventBuffer(key, clockMs, sampleCapacity, boundaryCapacity, retentionMs)
        active!!.offer(RealtimeObservation(key, ObservationKind.START, clockMs()))
        notifySink(RealtimeObservation(key, ObservationKind.START, clockMs()))
    }

    fun stop(driveId: String) = lock.withLock {
        if (session?.driveSessionId != driveId) return@withLock
        active?.let {
            it.offer(RealtimeObservation(session!!, ObservationKind.STOP, clockMs()))
            notifySink(RealtimeObservation(session!!, ObservationKind.STOP, clockMs()))
            lastClosed = it.snapshot().diagnostics
            it.close()
        }
        active = null
        session = null
    }

    fun event(event: EventDto) {
        val value = RealtimeObservation.fromEvent(runId, event)
        if (value == null) { inactive.incrementAndGet(); return }
        offer(value)
    }

    fun speed(driveId: String, speedKmh: Double, elapsedMs: Long, receivedAt: String,
              playbackId: String?, musicId: String?, sourceSampleId: String? = null, sourceObservedMs: Long? = null) {
        offer(RealtimeObservation(RealtimeSession(runId, driveId), ObservationKind.SPEED, elapsedMs,
            receivedAt, speedKmh = speedKmh, playbackSessionId = playbackId, musicId = musicId,
            sourceSampleId = sourceSampleId, sourceObservedMs = sourceObservedMs))
    }

    fun offer(value: RealtimeObservation): OfferResult? {
        offered.incrementAndGet()
        if (!lock.tryLock()) {
            contention.incrementAndGet()
            sessionLoss.set(true)
            lastLossMs.set(clockMs())
            return null
        }
        return try {
            if (value.session != session) {
                inactive.incrementAndGet()
                null
            } else active?.offer(value)?.also {
                if (it != OfferResult.ACCEPTED && it != OfferResult.DUPLICATE) sessionLoss.set(true)
                if (it == OfferResult.ACCEPTED) {
                    accepted.incrementAndGet()
                    if (value.kind == ObservationKind.SPEED) speedAccepted.incrementAndGet()
                    notifySink(value)
                }
            }
        } catch (_: Exception) {
            failures.incrementAndGet()
            sessionLoss.set(true)
            lastLossMs.set(clockMs())
            null
        } finally { lock.unlock() }
    }

    fun snapshot(): RealtimeSnapshot? = lock.withLock { active?.snapshot() }
    fun lastClosedDiagnostics(): BufferDiagnostics? = lock.withLock { lastClosed }
    fun diagnostics() = RealtimeIngressDiagnostics(offered.get(), accepted.get(), speedAccepted.get(),
        inactive.get(), contention.get(), failures.get())
    fun hasInputLoss(): Boolean = sessionLoss.get()

    fun context(): DrivingContext? {
        val snapshot = snapshot() ?: return null
        if (snapshot.state != BufferState.OPEN) return null
        return DrivingContextProcessor().process(snapshot, clockMs())
    }

    /** Consumer gate. Stale, incomplete or recently dropped input yields unknown (null). */
    fun usableContext(): DrivingContext? {
        val value = context() ?: return null
        val now = clockMs()
        val loss = lastLossMs.get()
        if (value.quality != ContextQuality.AVAILABLE || loss >= 0 && now - loss <= 10_000 ||
            value.qualityFlags.any { it in setOf("sample_overflow", "boundary_overflow", "boundary_rejected", "upstream_loss",
                "observation_reversal", "missing_start") }) return null
        return lock.withLock { value.takeIf { it.session == session } }
    }
}
