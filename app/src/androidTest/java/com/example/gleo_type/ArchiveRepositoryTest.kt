package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArchiveRepositoryTest {
    @Test fun bufferedHistoryRetriesSplitUtf8AndDoesNotDuplicateLargeSnapshots() {
        val dir = directory(); val file = File(dir, "utf8.jsonl")
        val row = """{"kind":"session_started","runId":"utf8","driveSessionId":"drive","localUserId":"synthetic","occurredAt":"2026-11-10T00:00:00Z","note":"한글 메모"}""".toByteArray(Charsets.UTF_8)
        val split = row.indexOfFirst { it.toInt() < 0 } + 1
        file.writeBytes(row.copyOfRange(0, split))
        val repo = JsonlHistoryRepository(dir, "synthetic")
        assertTrue(repo.refresh().drives.isEmpty())
        file.appendBytes(row.copyOfRange(split, row.size) + byteArrayOf(10))
        val sample = """{"kind":"speed_sample","runId":"utf8","driveSessionId":"drive","localUserId":"synthetic","occurredAt":"2026-11-10T00:00:01Z","speedKmh":36,"receivedElapsedMs":1000}"""
        file.appendText((sample + "\n").repeat(2000), Charsets.UTF_8)
        assertEquals(2000, repo.refresh().drives.single().speedSampleCount)
        assertEquals(2000, repo.refresh().drives.single().speedSampleCount)
        assertEquals(0, repo.refresh().malformedLines)
    }
    @Test fun recommendationRunsPersistPriorResultsAndCorruptionNeverOverwritesOriginal() {
        val dir = directory(); val repo = LocalRecommendationHistory(dir)
        val catalog = listOf(Track("one", "한글 곡", "테스트"), Track("two", "곡 2", "테스트"))
        val first = LocalRecommendationBuilder.build(catalog, MusicScope.ALL, emptySet(), emptySet(), 0, null, "synthetic")
        val second = LocalRecommendationBuilder.build(catalog, MusicScope.ALL, emptySet(), emptySet(), 1, null, "synthetic")
        repo.append(first); repo.append(second); repo.append(first)
        assertEquals(listOf(first, second), LocalRecommendationHistory(dir).load())
        val file = File(dir, "runs.jsonl"); file.appendText("partial", Charsets.UTF_8)
        val original = file.readBytes()
        assertTrue(runCatching { repo.append(first.copy(id = "third")) }.isFailure)
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun optionalAttachmentMetadataRoundTripsAndLegacyPinsRemainCompatible() {
        val dir = directory(); val repo = LocalArchiveRepository(dir)
        val legacy = Pin("legacy", "2026-11-10T00:00:00Z", null, null, null, emptyList(), "한글")
        repo.save(legacy); assertEquals(legacy, repo.load().single())
        val reference = ArchiveAttachment("future", "photo", "content://synthetic/not-opened", "사진 메타데이터", "image/jpeg")
        val extended = legacy.copy(id = "extended", attachments = listOf(reference))
        repo.save(extended)
        assertEquals(listOf(legacy, extended), LocalArchiveRepository(dir).load())
        assertTrue(runCatching { reference.copy(localReference = "https://not-uploaded") }.isFailure)
    }
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "repo-test-${UUID.randomUUID()}").apply { mkdirs() }
    @Test fun pinsPersistWithoutInventingLocationAndDeleteOnlyArchiveEntry() {
        val dir = directory()
        val pin = Pin("synthetic", "2026-11-10T00:00:00Z", "synthetic-drive", Track("test", "한글 음악", "테스트"), null, listOf("테스트"), "한글 메모")
        LocalArchiveRepository(dir).save(pin)
        assertEquals(pin, LocalArchiveRepository(dir).load().single())
        LocalArchiveRepository(dir).delete(pin.id)
        assertTrue(LocalArchiveRepository(dir).load().isEmpty())
        LocalArchiveRepository(dir).save(pin)
        assertNull(LocalArchiveRepository(dir).load().single().place)
    }
    @Test fun incrementalJournalHandlesPartialLineAndIgnoresOtherProfile() {
        val dir = directory()
        val file = File(dir, "synthetic.jsonl")
        val start = """{"kind":"session_started","runId":"run","driveSessionId":"drive","localUserId":"local_synthetic","occurredAt":"2026-11-10T00:00:00Z"}"""
        file.writeText(start.take(40), Charsets.UTF_8)
        val repo = JsonlHistoryRepository(dir, "local_synthetic")
        assertTrue(repo.refresh().drives.isEmpty())
        file.appendText(start.drop(40) + "\n", Charsets.UTF_8)
        val interval = """{"kind":"listening_interval","runId":"run","driveSessionId":"drive","localUserId":"local_synthetic","playbackSessionId":"pb","musicId":"test","intervalIndex":"1","creditedWallMs":"1000","occurredAt":"2026-11-10T00:00:01Z"}"""
        file.appendText(interval + "\n", Charsets.UTF_8)
        assertEquals(1000.0, repo.refresh().listeningWallMs, .001)
        assertEquals(1000.0, repo.refresh().listeningWallMs, .001)
        file.appendText(interval + "\n", Charsets.UTF_8) // duplicated interval index is idempotent
        file.appendText(start.replace("local_synthetic", "other-profile").replace("drive\"", "other-drive\"") + "\n", Charsets.UTF_8)
        file.appendText("broken row\n", Charsets.UTF_8)
        val result = repo.refresh()
        assertEquals(1, result.drives.size); assertEquals(1000.0, result.listeningWallMs, .001)
        assertEquals(1, result.malformedLines); assertNull(result.drives.single().place)
        assertNull(result.drives.single().durationMs)
        assertEquals(listOf("test"), result.drives.single().tracks)
    }
    @Test fun separateRepositoryInstancesSerializeAtomicWritesAndReads() {
        val dir = directory()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..20).map { i -> executor.submit {
                LocalArchiveRepository(dir).save(Pin("test-$i", "2026-11-10T00:00:00Z", null, null, null, emptyList(), "테스트"))
                LocalArchiveRepository(dir).load()
            } }
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals((1..20).map { "test-$it" }.toSet(), LocalArchiveRepository(dir).load().map { it.id }.toSet())
        } finally { executor.shutdownNow() }
    }
    @Test fun corruptedArchiveIsNeverOverwrittenBySave() {
        val dir = directory()
        val file = File(dir, "pins.json").apply { writeText("corrupted original", Charsets.UTF_8) }
        assertTrue(runCatching { LocalArchiveRepository(dir).save(Pin("test", "now", null, null, null, emptyList(), "test")) }.isFailure)
        assertEquals("corrupted original", file.readText(Charsets.UTF_8))
    }
    @Test fun recommendationFeedbackStoresExplicitFactsAndNeverGuessesRejection() {
        val dir = directory()
        val feedback = LocalRecommendationFeedback(dir, "synthetic")
        feedback.record("recommendation_excluded", "한글_music", "drive", null, "2026-11-10T00:00:00Z", 1000)
        feedback.record("recommendation_restored", "한글_music", "drive", null, "2026-11-10T00:00:01Z", 2000)
        val rows = File(dir, "recommendation-feedback.jsonl").readLines(Charsets.UTF_8).map { org.json.JSONObject(it) }
        assertEquals(listOf("recommendation_excluded", "recommendation_restored"), rows.map { it.getString("action") })
        assertTrue(rows.all { it.getString("musicId") == "한글_music" && it.getString("preferenceInference") == "none" && it.isNull("playbackSessionId") })
        assertTrue(runCatching { feedback.record("rejected", "test", null, null, "now", 3000) }.isFailure)
    }
}
