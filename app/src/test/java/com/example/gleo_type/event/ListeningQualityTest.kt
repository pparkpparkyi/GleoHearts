package com.example.gleo_type.event

import com.example.gleo_type.event.music.ListeningTracker
import com.example.gleo_type.event.core.LocalUserIdentity
import com.example.gleo_type.event.send.JsonlEventSender
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListeningQualityTest {
    @get:Rule val temp = TemporaryFolder()
    private val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
    private fun tracker() = ListeningTracker(write = { kind, _, _, fields -> rows += kind to fields })
    private fun sample(t: Long, p: Long?, playing: Boolean = true, speed: Double = 1.0, duration: Long? = 216555) =
        ListeningTracker.Sample("drive", "playback", "jamendo_0016", t, "callback", p, duration, playing, speed, "test")
    private fun summary() = rows.last { it.first == "listening_summary" }.second
    private fun number(key: String) = summary()[key].toString().toDouble()

    @Test fun seekingToEndAndCompletingDoesNotMeanFullListening() {
        val tracker = tracker()
        tracker.observe(sample(0, 0)); tracker.observe(sample(1000, 1000))
        tracker.discontinuity("playback", 1500, 1500, "callback", "seek")
        tracker.observe(sample(1500, 216055)); tracker.observe(sample(2000, 216555, false))
        tracker.finishPlayback("playback", 2000, "callback", "natural_end")
        assertEquals(2000.0, number("totalListeningWallMs"), 0.01)
        assertEquals(2000.0, number("uniqueListenedMediaMs"), 0.01)
        assertTrue(number("uniqueListeningRate") < 0.01)
    }

    @Test fun replayCountsWallTimeTwiceButCoverageOnce() {
        val tracker = tracker()
        tracker.observe(sample(0, 0)); tracker.observe(sample(1000, 1000))
        tracker.discontinuity("playback", 1000, 1000, "callback", "seek")
        tracker.observe(sample(1000, 0)); tracker.observe(sample(2000, 1000))
        tracker.finishCollection("drive", 2000, "callback", true)
        assertEquals(2000.0, number("totalListeningWallMs"), 0.01)
        assertEquals(1000.0, number("uniqueListenedMediaMs"), 0.01)
    }

    @Test fun pauseBufferingAndSpeedUsePreviousPlayingState() {
        val tracker = tracker()
        tracker.observe(sample(0, 0, speed = 2.0))
        tracker.observe(sample(1000, 2000, false))
        tracker.observe(sample(2000, 2000, false))
        tracker.observe(sample(3000, 2000, true))
        tracker.observe(sample(4000, 3000, false))
        tracker.finishPlayback("playback", 4000, "callback", "stop")
        assertEquals(2000.0, number("totalListeningWallMs"), 0.01)
        assertEquals(3000.0, number("totalListenedMediaMs"), 0.01)
    }

    @Test fun gapsUnknownPositionsAndMissingFinalSnapshotAreNotInvented() {
        val tracker = tracker()
        tracker.observe(sample(0, 0, duration = null)); tracker.observe(sample(4000, 4000, duration = null))
        tracker.observe(sample(5000, null, duration = null))
        tracker.finishCollection("drive", 5500, "callback", false)
        assertEquals(0.0, number("uniqueListenedMediaMs"), 0.01)
        assertNull(summary()["uniqueListeningRate"])
        val flags = summary()["qualityFlags"] as List<*>
        assertTrue(flags.containsAll(listOf("observation_gap", "missing_position", "unknown_duration", "missing_final_snapshot", "unobserved_tail_excluded")))
        assertEquals("incomplete_lower_bound", summary()["confidence"])
    }

    @Test fun lateCallbacksCannotReopenClosedSessionAndNewDriveCanStart() {
        val tracker = tracker()
        tracker.observe(sample(0, 0)); tracker.observe(sample(1000, 1000))
        tracker.finishCollection("drive", 1000, "callback", true)
        val count = rows.size
        tracker.observe(sample(2000, 2000)); assertEquals(count, rows.size)
        tracker.observe(sample(4000, 4000).copy(driveId = "drive2"))
        val newCount = rows.size
        tracker.observe(sample(4500, 4500)) // Delayed old-drive callback cannot close drive2.
        assertEquals(newCount, rows.size)
        tracker.observe(sample(5000, 5000).copy(driveId = "drive2"))
        tracker.finishCollection("drive2", 5000, "callback", true)
        assertEquals(1000.0, number("uniqueListenedMediaMs"), 0.01)
    }

    @Test fun clockRegressionAndUnreportedJumpAreExcluded() {
        val tracker = tracker()
        tracker.observe(sample(1000, 1000)); tracker.observe(sample(900, 900))
        tracker.observe(sample(1900, 200000)); tracker.finishCollection("drive", 1900, "callback", true)
        assertEquals(0.0, number("uniqueListenedMediaMs"), 0.01)
        assertTrue((summary()["qualityFlags"] as List<*>).contains("monotonic_clock_regression"))
    }

    @Test fun identityPersistsAndJournalKeepsAccountSeparate() {
        val directory = temp.newFolder()
        val id = LocalUserIdentity(directory).id
        assertEquals(id, LocalUserIdentity(directory).id)
        assertNotEquals(id, LocalUserIdentity(temp.newFolder()).id)
        val events = temp.newFolder()
        assertTrue(JsonlEventSender(events, localUserId = id).append("test", "drive", "t", "TEST", "test").isSuccess)
        val row = TestJson.parse(events.listFiles()!!.single().readText().trim()) as Map<*, *>
        assertEquals(id, row["localUserId"]); assertNull(row["accountUserId"])
        assertEquals("installation_pseudonym", row["identityKind"])
    }

    @Test(expected = IllegalStateException::class) fun corruptIdentityDoesNotSilentlyChangePerson() {
        val directory = temp.newFolder()
        directory.resolve("local-profile-id").writeText("broken")
        LocalUserIdentity(directory)
    }
}
