package com.example.gleo_type.realtime

import com.example.gleo_type.event.SourceTestHarness
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.vehicle.VehicleData
import com.example.gleo_type.event.send.EventPublisher
import com.example.gleo_type.event.send.EventSender
import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.json.JsonWriter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RealtimeSdkIntegrationTest {
    private fun event(): EventDto = SourceTestHarness().factory.create(
        VehiclePayload(MusicSummaryDto.EMPTY, VehicleData.Speed("36.0")))

    @Test fun contendedCallbackReturnsWithoutWaitingForConsumer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pipeline = RealtimePipeline("run", {
            if (Thread.currentThread().name == "slow-snapshot") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            10000L
        })
        pipeline.start("drive")
        val reader = Thread({ pipeline.snapshot() }, "slow-snapshot")
        reader.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertNull(pipeline.offer(RealtimeObservation(RealtimeSession("run", "drive"),
                ObservationKind.SPEED, 10000, speedKmh = 36.0)))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
            assertEquals(1L, pipeline.diagnostics().contentionDropped)
        } finally { release.countDown(); reader.join(5000) }
        assertFalse(reader.isAlive)
    }

    @Test fun realtimeSurvivesStorageQueueSaturationAndObserverFailure() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var sent = 0
        var storedFailures = 0
        var observed = 0
        val job = Job()
        val sender = object : EventSender {
            override suspend fun send(event: EventDto): Result<Unit> {
                release.await(); sent++; return Result.success(Unit)
            }
        }
        val publisher = EventPublisher(sender, CoroutineScope(job + Dispatchers.Unconfined)) { _, _ -> storedFailures++ }
        publisher.realtimeObserver = { observed++ }
        repeat(1100) { publisher.publish(event()) }
        assertEquals(1100, observed)
        assertTrue(storedFailures > 0)
        release.complete(Unit)
        assertEquals(1100, sent + storedFailures)
        publisher.realtimeObserver = { throw IllegalArgumentException("synthetic observer failure") }
        publisher.publish(event())
        assertEquals(1L, publisher.realtimeObserverFailures.get())
        assertEquals(1101, sent + storedFailures)
        job.cancel()
    }

    @Test fun sessionsFreshnessDuplicateAndReversalGate() {
        var now = 0L
        val pipeline = RealtimePipeline("run", { now })
        pipeline.start("one")
        for (i in 0..10) {
            now = i * 1000L
            pipeline.speed("one", 36.0, now, "2026-11-11T00:00:00Z", "pb", "track")
        }
        assertNotNull(pipeline.usableContext())
        repeat(400) { pipeline.offer(RealtimeObservation(RealtimeSession("run", "one"),
            ObservationKind.OTHER, now, eventId = "synthetic-$it")) }
        assertNull(pipeline.usableContext())
        now += 3000
        assertNull(pipeline.usableContext())
        pipeline.stop("one")
        assertNull(pipeline.snapshot()); assertNull(pipeline.usableContext())
        assertFalse(pipeline.lastClosedDiagnostics()!!.missingStop)
        pipeline.start("two")
        pipeline.speed("one", 10.0, now, "t", null, null)
        assertEquals(0, pipeline.snapshot()!!.entries.count { it.value.kind == ObservationKind.SPEED })
        val e = event().copy(driveSessionId = "two")
        pipeline.event(e); pipeline.event(e)
        assertEquals(1L, pipeline.snapshot()!!.diagnostics.duplicates)
        pipeline.speed("two", 0.0, now, "t", null, null)
        pipeline.speed("two", 0.0, now - 1, "t", null, null)
        assertTrue(pipeline.snapshot()!!.diagnostics.reversals > 0)
        val restarted = RealtimePipeline("new-run", { now })
        restarted.start("two")
        restarted.offer(RealtimeObservation(RealtimeSession("run", "two"), ObservationKind.SPEED, now, speedKmh = 10.0))
        assertEquals(0L, restarted.diagnostics().speedAccepted)
    }

    @Test fun loadCoverageAndAllocation() {
        val results = mutableListOf<Map<String, String>>()
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        for (capacity in listOf(4, 16, 64, 256)) for (retention in listOf(5000L, 120000L)) {
            var now = 0L
            val key = RealtimeSession("synthetic", "load")
            val before = bean?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
                ?.getThreadAllocatedBytes(Thread.currentThread().id)
            val buffer = RealtimeEventBuffer(key, { now }, capacity, 4, retention)
            buffer.offer(RealtimeObservation(key, ObservationKind.START, 0))
            for (i in 0..10000) {
                now = i.toLong()
                if (i % 1000 == 0) buffer.offer(RealtimeObservation(key, ObservationKind.SPEED, now, speedKmh = 36.0))
                buffer.offer(RealtimeObservation(key, ObservationKind.VEHICLE, now, eventId = "synthetic-$i"))
                if (i % 1000 == 0) buffer.offer(RealtimeObservation(key, ObservationKind.MUSIC, now, eventId = "music-$i", label = "PAUSED"))
            }
            val snapshot = buffer.snapshot()
            val context = DrivingContextProcessor().process(snapshot, now)
            assertTrue(snapshot.entries.size <= capacity + 4)
            assertTrue(snapshot.diagnostics.sampleOverflow > 0)
            assertTrue(snapshot.diagnostics.boundaryOverflow > 0)
            if (capacity >= 16 && retention > 10000) assertEquals(1.0, context.coverage, 0.0)
            if (retention == 5000L) assertTrue(context.coverage <= .5)
            results += mapOf("capacity" to "$capacity", "retentionMs" to "$retention",
                "retained" to "${snapshot.entries.size}", "coverage" to "${context.coverage}",
                "sampleOverflow" to "${snapshot.diagnostics.sampleOverflow}",
                "speedEvicted" to "${snapshot.diagnostics.speedCapacityEvicted}",
                "expired" to "${snapshot.diagnostics.expired}",
                "boundaryOverflow" to "${snapshot.diagnostics.boundaryOverflow}",
                "threadAllocatedBytes" to "${before?.let { bean!!.getThreadAllocatedBytes(Thread.currentThread().id) - it }}")
        }
        System.getenv("GLEO_REALTIME_LOAD_OUTPUT")?.let {
            File(it).writeText(JsonWriter.write(results), Charsets.UTF_8)
        }
    }
}
