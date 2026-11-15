package com.example.gleo_type.realtime

import com.example.gleo_type.event.json.JsonWriter
import com.example.gleo_type.event.send.JsonlEventSender
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class RealtimePipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = RealtimeSession("run", "drive")
    private var now = 10_000L
    private fun speed(at: Long, value: Double) = RealtimeObservation(key, ObservationKind.SPEED, at, speedKmh = value)

    @Test fun weightedFeaturesAndRepeatedZero() {
        val buffer = RealtimeEventBuffer(key, { now })
        listOf(speed(0, 0.0), speed(2000, 0.0), speed(3000, 30.0), speed(4000, 60.0)).forEach {
            assertEquals(OfferResult.ACCEPTED, buffer.offer(it))
        }
        val value = DrivingContextProcessor(windowMs = 4000).process(buffer.snapshot(), 4000)
        assertEquals(4, value.sampleCount)
        assertEquals(4000L, value.observedDurationMs)
        assertEquals(7.5, value.averageSpeedKmh!!, 1e-9)
        assertEquals(kotlin.math.sqrt(168.75), value.speedStdDevKmh!!, 1e-9)
        assertEquals(.75, value.stationaryRatio!!, 1e-9)
        assertEquals(60.0, value.maxSpeedKmh!!, 1e-9)
        assertEquals(60.0, value.speedChangeKmh!!, 1e-9)
        assertEquals(ContextQuality.AVAILABLE, value.quality)
        assertNull(value.brakeActivations); assertNull(value.steeringAngle); assertNull(value.cruiseActive)
    }

    @Test fun emptySingleAndGappedSamplesRemainInsufficient() {
        val buffer = RealtimeEventBuffer(key, { now })
        val processor = DrivingContextProcessor()
        assertEquals(SampleFreshness.UNAVAILABLE, processor.process(buffer.snapshot(), now).freshness)
        buffer.offer(speed(0, 0.0))
        assertNull(processor.process(buffer.snapshot(), now).averageSpeedKmh)
        buffer.offer(speed(9000, 0.0))
        val value = processor.process(buffer.snapshot(), now)
        assertEquals(0.0, value.coverage, 0.0)
        assertTrue("observation_gap" in value.qualityFlags)
        assertEquals(ContextQuality.INSUFFICIENT_DATA, value.quality)
    }

    @Test fun rejectsDuplicatesOtherSessionsAndInvalidValues() {
        val buffer = RealtimeEventBuffer(key, { now })
        val event = RealtimeObservation(key, ObservationKind.MUSIC, null, eventId = "event")
        assertEquals(OfferResult.ACCEPTED, buffer.offer(event))
        assertEquals(OfferResult.DUPLICATE, buffer.offer(event))
        assertEquals(OfferResult.WRONG_SESSION, buffer.offer(event.copy(session = key.copy(runId = "other"))))
        assertEquals(OfferResult.WRONG_SESSION, buffer.offer(event.copy(session = key.copy(driveSessionId = "other"))))
        assertEquals(OfferResult.INVALID, buffer.offer(speed(now, Double.NaN)))
        assertEquals(OfferResult.INVALID, buffer.offer(speed(now + 1, 0.0)))
        assertEquals(1L, buffer.snapshot().diagnostics.missingObservedTime)
        assertEquals(5L, buffer.snapshot().diagnostics.rejected)
    }

    @Test fun overflowExpiryAndReversalAreObservable() {
        val buffer = RealtimeEventBuffer(key, { now }, 2, 1, retentionMs = 100)
        buffer.offer(RealtimeObservation(key, ObservationKind.START, null))
        buffer.offer(speed(9999, 0.0)); buffer.offer(speed(9998, 0.0)); buffer.offer(speed(now, 0.0))
        buffer.offer(RealtimeObservation(key, ObservationKind.MUSIC, now, eventId = "play"))
        buffer.reportUpstreamMissing()
        val d = buffer.snapshot().diagnostics
        assertEquals(3, buffer.snapshot().entries.size)
        assertEquals(1L, d.reversals); assertEquals(1L, d.sampleOverflow)
        assertEquals(1L, d.boundaryOverflow); assertEquals(1L, d.upstreamMissing)
        now += 101
        assertTrue(buffer.snapshot().entries.isEmpty())
        assertEquals(3L, buffer.snapshot().diagnostics.expired)
        assertEquals(OfferResult.TOO_OLD, buffer.offer(speed(9999, 0.0)))
    }

    @Test fun stopAndIdleDoNotReopenOrInventBoundaries() {
        val buffer = RealtimeEventBuffer(key, { now }, idleTimeoutMs = 100)
        buffer.offer(speed(now, 0.0))
        now += 100
        val expired = buffer.snapshot()
        assertEquals(BufferState.IDLE_EXPIRED, expired.state)
        assertTrue(expired.diagnostics.missingStart && expired.diagnostics.missingStop)
        assertTrue(expired.entries.isEmpty())
        assertEquals(OfferResult.CLOSED, buffer.offer(speed(now, 0.0)))
        val stopped = RealtimeEventBuffer(key, { now })
        stopped.offer(RealtimeObservation(key, ObservationKind.START, null))
        stopped.offer(RealtimeObservation(key, ObservationKind.STOP, null))
        assertEquals(BufferState.STOPPED, stopped.snapshot().state)
        assertFalse(stopped.snapshot().diagnostics.missingStop)
        assertEquals(OfferResult.CLOSED, stopped.offer(speed(now, 0.0)))
        stopped.close()
        assertTrue(stopped.snapshot().entries.isEmpty())
    }

    @Test fun concurrentInputMaintainsOneAcceptancePerId() {
        val buffer = RealtimeEventBuffer(key, { now })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                executor.submit {
                    repeat(40) { id -> buffer.offer(RealtimeObservation(key, ObservationKind.VEHICLE, now, eventId = "$id")) }
                }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            val snapshot = buffer.snapshot()
            assertEquals(40, snapshot.entries.size)
            assertEquals(120L, snapshot.diagnostics.duplicates)
            assertEquals((1L..40L).toList(), snapshot.entries.map { it.arrivalOrder })
        } finally { executor.shutdownNow() }
    }

    @Test fun rejectedRealtimeInputCannotConsumeJournalSequence() {
        val directory = temporary.newFolder()
        val sender = JsonlEventSender(directory)
        val buffer = RealtimeEventBuffer(key, { now })
        assertTrue(sender.append("session_started", "drive", "2026-11-11T00:00:00Z", "TEST", "lifecycle").isSuccess)
        assertEquals(OfferResult.INVALID, buffer.offer(speed(now, Double.POSITIVE_INFINITY)))
        buffer.close()
        assertEquals(OfferResult.CLOSED, buffer.offer(speed(now, 0.0)))
        assertTrue(sender.append("session_stopped", "drive", "2026-11-11T00:00:01Z", "TEST", "lifecycle").isSuccess)
        val rows = directory.listFiles()!!.single().readLines()
        assertEquals(2, rows.size)
        assertTrue(rows[1].contains("\"sequence\":\"2\""))
    }

    @Test fun realReplay() {
        val root = System.getenv("GLEO_REALTIME_REPLAY_DIR")
        assumeTrue("Explicit replay directory required", !root.isNullOrBlank())
        for (environment in listOf("official", "aaos")) {
            val file = File(root, "$environment.tsv")
            val first = replay(file, System.getenv("GLEO_REALTIME_REPLAY_RATE")?.toDouble() ?: 0.0)
            assertEquals(first, replay(file, 0.0))
            assertTrue((first.getValue("speedSamples") as Int) > 0)
            val report = first.mapValues { (_, value) -> value.toString() }
            File(root, "$environment-replay.json").writeText(JsonWriter.write(report) + "\n", Charsets.UTF_8)
        }
    }

    private fun replay(file: File, rate: Double): Map<String, Any?> {
        require(rate.isFinite() && rate >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val processor = DrivingContextProcessor()
        var samples = 0
        var available = 0
        var missingStops = 0
        var missingStarts = 0
        var reversals = 0L
        var rejected = 0L
        var overflow = 0L
        var boundaryOverflow = 0L
        val grouped = file.readLines(Charsets.UTF_8).map { line ->
            val p = line.split('\t')
            fun optional(index: Int) = p[index].takeIf(String::isNotEmpty)
            RealtimeObservation(RealtimeSession(p[0], p[1]), ObservationKind.valueOf(p[2]),
                optional(3)?.toLong(), optional(4), optional(5), optional(6)?.toDouble(), optional(7), optional(8),
                p.getOrNull(9)?.takeIf(String::isNotEmpty), p.getOrNull(10)?.takeIf(String::isNotEmpty))
        }.groupBy { it.session }
        for ((session, rows) in grouped) {
            var tick = rows.firstNotNullOfOrNull { it.observedMs } ?: 0L
            RealtimeEventBuffer(session, { tick }).use { buffer ->
                for (row in rows) {
                    val next = maxOf(tick, row.observedMs ?: tick)
                    if (rate > 0) Thread.sleep(((next - tick) / rate).toLong())
                    tick = next
                    if (buffer.offer(row) == OfferResult.ACCEPTED && row.kind == ObservationKind.SPEED) {
                        samples++
                        val features = processor.process(buffer.snapshot(), tick)
                        if (features.quality == ContextQuality.AVAILABLE) available++
                        digest.update(features.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                val d = buffer.snapshot().diagnostics
                if (d.missingStop) missingStops++
                if (d.missingStart) missingStarts++
                reversals += d.reversals
                rejected += d.rejected
                overflow += d.sampleOverflow
                boundaryOverflow += d.boundaryOverflow
            }
        }
        return linkedMapOf("sessions" to grouped.size, "inputRows" to grouped.values.sumOf { it.size },
            "speedSamples" to samples, "availableWindows" to available, "missingStarts" to missingStarts,
            "missingStops" to missingStops, "reversals" to reversals, "rejected" to rejected,
            "sampleOverflow" to overflow, "boundaryOverflow" to boundaryOverflow,
            "featureSha256" to digest.digest().joinToString("") { "%02x".format(it) })
    }
}
