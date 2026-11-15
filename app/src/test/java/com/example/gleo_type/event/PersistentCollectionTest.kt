package com.example.gleo_type.event

import com.example.gleo_type.event.core.*
import com.example.gleo_type.event.vehicle.*
import com.example.gleo_type.event.send.*
import com.example.gleo_type.event.json.EventJsonSerializer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PersistentCollectionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun event() = SourceTestHarness().factory.create(
        VehiclePayload(com.example.gleo_type.event.music.MusicStateHolder().currentSummary(), VehicleData.Speed("36.0")))
    private fun records(dir: File) = dir.listFiles()!!.sortedBy { it.name }
        .flatMap { it.readLines(Charsets.UTF_8) }.map { TestJson.parse(it) as Map<*, *> }

    @Test fun preservesSchemaAndPersistsAcrossWriterRestart() = runBlocking {
        val dir = temp.newFolder()
        val e = event()
        assertTrue(JsonlEventSender(dir).send(e).isSuccess)
        assertTrue(JsonlEventSender(dir).send(e).isSuccess)
        val rows = records(dir)
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(EventJsonSerializer().toJsonMap(e), it["event"]) }
        assertNotEquals(rows[0]["runId"], rows[1]["runId"])
    }

    @Test fun utf8KoreanNewlineAndEmojiRoundTrip() {
        val dir = temp.newFolder()
        val text = "서울 강남역\n도착 🚘"
        assertTrue(JsonlEventSender(dir).append("test", "s", "t", "TEST", "raw", mapOf("text" to text)).isSuccess)
        assertEquals(text, records(dir).single()["text"])
        assertEquals(1, dir.listFiles()!!.single().readLines(Charsets.UTF_8).size)
        val chunks = utf8LogChunks(text.repeat(1000), 3500)
        assertEquals(text.repeat(1000), chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 3500 })
    }

    @Test fun concurrentWritesHaveUniqueContiguousSequence() {
        val dir = temp.newFolder()
        val sender = JsonlEventSender(dir)
        val executor = Executors.newFixedThreadPool(8)
        val tasks = (1..100).map { executor.submit<Boolean> {
            sender.append("test", "s", "t", "TEST", "raw").isSuccess
        } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(tasks.all { it.get() })
        assertEquals((1..100).map { it.toString() }, records(dir).map { it["sequence"] })
    }

    @Test fun storageErrorsAreFailuresAndDoNotConsumeSequence() {
        val path = temp.newFile()
        val sender = JsonlEventSender(path)
        assertTrue(sender.append("test", null, "t", "TEST", "raw").isFailure)
        assertTrue(path.delete())
        assertTrue(sender.append("test", null, "t", "TEST", "raw").isSuccess)
        assertEquals("1", records(path).single()["sequence"])
    }

    @Test fun rotatesAndRefusesToSilentlyDeleteAtStorageLimit() {
        val dir = temp.newFolder()
        val sender = JsonlEventSender(dir, maxFileBytes = 700, maxDirectoryBytes = 1400)
        assertTrue(sender.append("test", null, "t", "TEST", "raw").isSuccess)
        assertTrue(sender.append("test", null, "t", "TEST", "raw").isSuccess)
        repeat(20) { sender.append("test", null, "t", "TEST", "raw") }
        assertTrue(dir.listFiles()!!.size >= 2)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 1400)
        assertTrue(sender.append("test", null, "t", "TEST", "raw").isFailure)
    }

    @Test fun sessionIsStableUntilStopAndFactoryUsesIt() {
        var ids = 0
        val sessions = DriveSessionManager { "s" + ++ids }
        assertNull(sessions.currentDriveSessionId())
        assertEquals("s1", sessions.start())
        assertEquals("s1", sessions.start())
        val factory = EventFactory(sessionProvider = sessions)
        assertEquals("s1", factory.create(event().payload).driveSessionId)
        assertEquals("s1", sessions.stop())
        assertNull(sessions.stop())
        assertNull(factory.create(event().payload).driveSessionId)
        assertEquals("s2", sessions.start())
    }

    @Test fun samplesRetainConstantSpeedAndRejectInvalidAndTooFrequentInputs() {
        val values = mutableListOf<Float>()
        val recorder = SpeedSampleRecorder { speed, _, _ -> values += speed }
        recorder.accept(10f, 1000, 1)
        recorder.accept(10f, 1500, 2)
        recorder.accept(Float.NaN, 2000, 3)
        recorder.accept(10f, 2000, 4)
        recorder.accept(-2f, 3000, 5)
        assertEquals(listOf(10f, 10f, -2f), values)
        recorder.reset()
        recorder.accept(0f, 3001, null)
        assertEquals(4, values.size)
    }

    @Test fun publisherSerializesSuspendingSendsAndReportsFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sender = object : EventSender {
            override suspend fun send(event: EventDto): Result<Unit> {
                if (event.eventId == "first") { entered.complete(Unit); release.await() }
                order += event.eventId
                return if (event.eventId == "bad") Result.failure(java.io.IOException("test")) else Result.success(Unit)
            }
        }
        var failures = 0
        val publisher = EventPublisher(sender, scope) { _, _ -> failures++ }
        publisher.publish(event().copy(eventId = "first"))
        withTimeout(5000) { entered.await() }
        publisher.publish(event().copy(eventId = "bad"))
        publisher.submit({ order += "aux"; finished.complete(Unit) }, { throw it })
        assertTrue(order.isEmpty())
        release.complete(Unit)
        withTimeout(5000) { finished.await() }
        assertEquals(listOf("first", "bad", "aux"), order)
        assertEquals(1, failures)
        scope.cancel()
    }
}
