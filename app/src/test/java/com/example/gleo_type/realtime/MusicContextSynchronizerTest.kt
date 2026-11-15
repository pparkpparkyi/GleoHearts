package com.example.gleo_type.realtime

import com.example.gleo_type.event.music.ListeningTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MusicContextSynchronizerTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = RealtimeSession("run", "drive")
    private var now = 10000L
    private val results = mutableListOf<PlaybackContextFeatures>()
    private fun engine(capacity: Int = 4096) = PlaybackContextAggregator({ now }, { results += it }, capacity).also {
        it.input(RealtimeObservation(key, ObservationKind.START, 0))
    }
    private fun PlaybackContextAggregator.music(label: String, time: Long, pb: String = "p", reason: String? = null) =
        input(RealtimeObservation(key, ObservationKind.MUSIC, time, playbackSessionId = pb, musicId = "song", label = label,
            transitionReason = reason))
    private fun PlaybackContextAggregator.speeds(vararg times: Long) = times.forEach {
        input(RealtimeObservation(key, ObservationKind.SPEED, it, speedKmh = if (it == 0L) 0.0 else 36.0))
    }
    private fun PlaybackContextAggregator.tracker() = ListeningTracker(write = { kind, _, _, fields -> listening(key, kind, fields) })
    private fun ListeningTracker.sample(time: Long, pos: Long, playing: Boolean = true, pb: String = "p") =
        observe(ListeningTracker.Sample("drive", pb, "song", time, "2026-11-11T00:00:00Z", pos, 10000,
            playing, 1.0, "test"))
    private fun PlaybackContextAggregator.finish(tracker: ListeningTracker, time: Long, label: String = "COMPLETED", pb: String = "p") {
        music(label, time, pb); tracker.finishPlayback(pb, time, "2026-11-11T00:00:00Z", "test_end")
    }
    private fun fields() = results.last().fields

    @Test fun normalUsesExistingListeningSummaryAndWeightedContext() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker()
        t.sample(0, 0); e.speeds(0, 1000, 2000); t.sample(2000, 2000); e.finish(t, 2000)
        assertEquals("available", fields()["contextQuality"])
        assertEquals(18.0, (fields()["avgSpeedKmh"] as String).toDouble(), 0.001)
        assertEquals("0.2", fields()["uniqueListeningRate"])
        assertEquals("2000.0", fields()["creditedWallMs"])
        assertNull(fields()["brakeEventsPerMin"])
    }

    @Test fun pauseAndBufferingExcludedResumeKeepsPlaybackId() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker()
        t.sample(0, 0); e.speeds(0, 1000); t.sample(1000, 1000, false); e.music("PAUSED", 1000)
        t.sample(4000, 1000); e.music("RESUMED", 4000)
        e.speeds(4000, 5000); t.sample(5000, 2000); e.finish(t, 5000, "SKIPPED")
        assertEquals("p", fields()["playbackSessionId"])
        assertEquals("2000.0", fields()["creditedWallMs"])
        assertEquals("1.0", fields()["observationCoverage"])
        assertEquals("SKIPPED", fields()["endReason"])
    }

    @Test fun seekToEndIsNotFullListeningAndRepeatedMediaUsesTrackerUnion() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker()
        t.sample(0, 0); e.speeds(0, 1000); t.sample(1000, 1000)
        t.discontinuity("p", 1000, 1000, "now", "seek")
        e.music("SEEKED", 1000); t.sample(1000, 9000)
        e.speeds(2000); t.sample(2000, 10000); e.finish(t, 2000)
        assertEquals("0.2", fields()["uniqueListeningRate"])
        assertEquals("2000.0", fields()["uniqueListenedMediaMs"])
        // A separate playback can repeat the same media; never merge playback identities.
        e.music("STARTED", 3000, "repeat"); t.sample(3000, 0, pb = "repeat")
        e.speeds(3000, 4000); t.sample(4000, 1000, pb = "repeat")
        e.music("COMPLETED", 4000, "repeat"); e.music("AUTO_TRANSITION", 4000, "repeat", "repeat")
        t.finishPlayback("repeat", 4000, "now", "track_transition:repeat")
        assertEquals(2, results.size); assertEquals("repeat", fields()["transitionReason"])
    }

    @Test fun reverseFutureAndCrossSessionNeverYieldUsableContext() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
        e.speeds(1000, 0, 2000); t.sample(2000, 2000); e.finish(t, 2000)
        assertEquals("unknown", fields()["contextQuality"]); assertNull(fields()["avgSpeedKmh"])
        val other = RealtimeSession("other", "drive")
        e.input(RealtimeObservation(other, ObservationKind.MUSIC, 2000, label = "STARTED", playbackSessionId = "alien"))
        assertNull(e.stateAt(other, 2000)); assertNull(e.stateAt(key, 1999)); assertNull(e.stateAt(key, 9000))
        val f = engine(); f.music("STARTED", 0); val u = f.tracker(); u.sample(0, 0)
        f.speeds(0, 11000); u.sample(1000, 1000); f.finish(u, 1000)
        assertEquals("unknown", fields()["contextQuality"])
    }

    @Test fun missingCarServiceSparseCapacityLossAndMissingStopRemainUnknown() {
        for (capacity in listOf(1, 4096)) {
            val e = engine(capacity); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
            if (capacity == 1) e.speeds(0, 1000, 2000)
            t.sample(2000, 2000); e.finish(t, 2000)
            assertEquals("unknown", fields()["contextQuality"])
        }
        val e = engine(); e.music("STARTED", 0)
        e.input(RealtimeObservation(RealtimeSession("run", "newDrive"), ObservationKind.START, 3000))
        assertNull(fields()["playbackEndElapsedMs"])
        assertNull(fields()["uniqueListeningRate"])
        assertEquals("unknown", fields()["contextQuality"])
        // App restart creates fresh state, cannot join previous run.
        assertNull(engine().stateAt(key, 0))
    }

    @Test fun duplicateIntervalsAndCollectionStopAreExplicit() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
        e.speeds(0, 1000); t.sample(1000, 1000)
        t.finishCollection("drive", 1000, "now", true)
        assertEquals("collection_stopped", fields()["endReason"])
        val count = results.size
        e.music("COMPLETED", 1000); assertEquals(count, results.size)
    }

    @Test fun backwardSeekRepeatsWallTimeButDoesNotRecalculateUniqueListening() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
        e.speeds(0, 1000, 2000); t.sample(2000, 2000)
        t.discontinuity("p", 2000, 2000, "now", "seek")
        e.music("SEEKED", 2000); t.sample(2000, 0)
        e.speeds(3000); t.sample(3000, 1000); e.finish(t, 3000)
        assertEquals("3000.0", fields()["creditedWallMs"])
        assertEquals("2000.0", fields()["uniqueListenedMediaMs"])
        assertEquals("0.2", fields()["uniqueListeningRate"])
        assertEquals("available", fields()["contextQuality"])
    }

    @Test fun duplicateIntervalsAndIngressLossCannotProduceUsableFeatures() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
        e.speeds(0, 1000); t.sample(1000, 1000)
        e.listening(key, "listening_interval", mapOf("playbackSessionId" to "p", "musicId" to "song",
            "intervalIndex" to "1", "fromElapsedMs" to "0", "toElapsedMs" to "1000", "creditedWallMs" to "1000.0"))
        e.markLoss("realtime_or_handoff_loss"); e.finish(t, 1000)
        assertEquals("1000.0", fields()["creditedWallMs"])
        assertEquals("unknown", fields()["contextQuality"])
        assertNull(fields()["avgSpeedKmh"])
    }

    @Test fun acceptedSpeedAfterPlaybackEndCannotFillCoverage() {
        val e = engine(); e.music("STARTED", 0); val t = e.tracker(); t.sample(0, 0)
        e.speeds(0, 3000) // Already received when finalizing, but future relative to playback's end.
        t.sample(2000, 2000); e.finish(t, 2000)
        assertEquals("unknown", fields()["contextQuality"])
        assertEquals("0.0", fields()["observationCoverage"])
        assertNull(fields()["avgSpeedKmh"])
    }

    @Test fun experimentalStoreIsOptInUtf8AppendOnlyAndFailureIsContained() {
        val dir = temp.newFolder("store"); val s = ExperimentalMusicContextStore(dir, "test")
        val value = PlaybackContextFeatures(mapOf("musicId" to "한글 음악", "avgSpeedKmh" to null))
        s.append(value); assertTrue(dir.listFiles()!!.isEmpty())
        s.enabled = true; s.append(value); s.append(value)
        assertEquals(2, dir.resolve("test.jsonl").readLines(Charsets.UTF_8).size)
        assertTrue(dir.resolve("test.jsonl").readText(Charsets.UTF_8).contains("한글 음악"))
        s.enabled = false; s.append(value); assertEquals(2L, s.written.get())
        val bad = ExperimentalMusicContextStore(temp.newFile("not-directory"), "test")
        bad.enabled = true; bad.append(value); assertEquals(1L, bad.failures.get())
    }
}
