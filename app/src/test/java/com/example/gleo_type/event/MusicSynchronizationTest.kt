package com.example.gleo_type.event

import com.example.gleo_type.event.core.*
import com.example.gleo_type.event.music.*
import com.example.gleo_type.event.send.JsonlEventSender
import com.example.gleo_type.event.json.EventJsonSerializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MusicSynchronizationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun snapshot() = MusicSnapshot("song", 10000, 4000, 8000, true, RepeatModeValue.OFF, false)

    @Test fun segmentIdsRotateOnlyWhenExplicitlyBeginningAnOccurrence() {
        var n = 0
        val ids = PlaybackSessions { "p${++n}" }
        assertNull(ids.currentId())
        assertEquals("p1", ids.begin())
        repeat(3) { assertEquals("p1", ids.currentId()) }
        assertEquals("p2", ids.begin()) // same song repeat/restart is also new occurrence
        ids.clear()
        assertNull(ids.currentId())
    }

    @Test fun seeksAreUnknownWithoutMatchingUiEvidence() {
        assertEquals(MusicLabel.UNKNOWN_TRANSITION, MusicTransitionClassifier.label("seek", null))
        assertEquals(MusicLabel.SKIPPED, MusicTransitionClassifier.label("seek", "next_button"))
        assertEquals(MusicLabel.SKIPPED, MusicTransitionClassifier.label("seek", "previous_button"))
        assertEquals(MusicLabel.UNKNOWN_TRANSITION, MusicTransitionClassifier.label("seek", "pause_button"))
        assertEquals(MusicLabel.AUTO_TRANSITION, MusicTransitionClassifier.label("auto", null))
        assertEquals(MusicLabel.AUTO_TRANSITION, MusicTransitionClassifier.label("repeat", null))
        assertEquals(MusicLabel.UNKNOWN_TRANSITION, MusicTransitionClassifier.label("playlist_changed", null))
    }

    @Test fun actionEvidenceExpiresAndCannotLeakAcrossSegments() {
        var time = 1000L
        val actions = MusicActionEvidence { time }
        actions.mark("next_button", "p1")
        assertNull(actions.consume(setOf("next_button"), "p2"))
        actions.mark("next_button", "p1")
        time += 2001
        assertNull(actions.consume(setOf("next_button"), "p1"))
        actions.mark("pause_button", "p1")
        assertNull(actions.consume(setOf("next_button"), "p1"))
        assertEquals("pause_button", actions.consume(setOf("pause_button"), "p1"))
        assertNull(actions.consume(setOf("pause_button"), "p1"))
    }

    @Test fun estimatedPositionKeepsSegmentAcrossPauseAndSeek() {
        var time = 0L
        val holder = MusicStateHolder { time }
        holder.update(snapshot().copy(playbackSessionId = "p1"))
        time = 1000
        assertEquals(5000L, holder.currentSnapshot()!!.currentPositionMs)
        assertEquals("p1", holder.currentSnapshot()!!.playbackSessionId)
        holder.update(snapshot().copy(isPlaying = false, playbackSessionId = "p1"))
        time = 5000
        assertEquals(4000L, holder.currentSnapshot()!!.currentPositionMs)
        holder.update(snapshot().copy(currentPositionMs = 2000, playbackSessionId = "p1"))
        assertEquals("p1", holder.currentSnapshot()!!.playbackSessionId)
    }

    @Test fun callbackTimesAndOldSegmentAreFrozenBeforeAsyncStorage() = runBlocking {
        val sessions = DriveSessionManager { "drive" }
        sessions.start()
        val factory = EventFactory(
            Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC),
            sessionProvider = sessions, elapsedRealtime = { 9999 }, playbackSessionId = { "new" })
        val observation = EventObservation("2026-11-09T00:00:01Z", 1000, "old",
            "seek", "next_button", "discontinuity_old_position", 4000, 1f)
        val event = factory.create(MusicEventMapper.state(MusicLabel.SKIPPED, snapshot()), observation)
        sessions.stop()
        val dir = temp.newFolder()
        assertTrue(JsonlEventSender(dir).send(event).isSuccess)
        val row = TestJson.parse(dir.listFiles()!!.single().readText().trim()) as Map<*, *>
        assertEquals("old", row["playbackSessionId"])
        assertEquals("1000", row["receivedElapsedMs"])
        assertEquals("drive", row["driveSessionId"])
        assertEquals(observation.receivedAt, event.timestamp)
        assertEquals(EventJsonSerializer().toJsonMap(event), row["event"])
        assertFalse((row["event"] as Map<*, *>).containsKey("observation"))
    }

    @Test fun completionNormalizationDoesNotOverwriteObservedPosition() {
        val payload = MusicEventMapper.state(MusicLabel.COMPLETED, snapshot())
        assertEquals("10000", payload.music.currentPositionMs)
        val observation = EventObservation("t", 1, observedPositionMs = 4000)
        assertEquals("4000", observation.fields()["observedPositionMs"])
        assertEquals("false", MusicEventMapper.state(MusicLabel.STOPPED, snapshot()).music.isPlaying)
    }

    @Test fun vehicleSummaryAndSegmentStayTogetherWhenPlayerHasAlreadyTransitioned() {
        val summary = MusicEventMapper.toSummary(snapshot().copy(playbackSessionId = "old"))
        val factory = EventFactory(playbackSessionId = { "new" })
        val event = factory.create(VehiclePayload(summary,
            com.example.gleo_type.event.vehicle.VehicleData.Speed("36.0")))
        assertEquals("old", event.observation!!.playbackSessionId)
        val musicJson = EventJsonSerializer().toJsonMap(event)["music"] as Map<*, *>
        assertEquals(setOf("musicId", "currentPositionMs", "isPlaying"), musicJson.keys)
    }
}
