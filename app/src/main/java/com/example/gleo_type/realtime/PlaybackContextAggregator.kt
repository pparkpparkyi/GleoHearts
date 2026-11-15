package com.example.gleo_type.realtime

/** Experimental derived data only. Listening quantities are copied from ListeningTracker. */
data class PlaybackContextFeatures(val fields: Map<String, Any?>)

/** Single-consumer state machine. No player access, IO, wall-clock joins or listening-rate calculation. */
class PlaybackContextAggregator(
    private val clockMs: () -> Long,
    private val emit: (PlaybackContextFeatures) -> Unit,
    private val speedCapacity: Int = 4096,
    private val playbackCapacity: Int = 32,
) : MusicContextSynchronizer {
    private data class Track(val id: String, var music: String?, var start: Long? = null,
        var end: Long? = null, var reason: String? = null, var transition: String? = null,
        var startReceivedAt: String? = null, var endReceivedAt: String? = null,
        var lastInterval: Long = 0, var lastEnd: Long = -1, var intervalCount: Long = 0,
        var credited: Double = 0.0,
        val windows: MutableList<DrivingCreditWindow> = mutableListOf(),
        val flags: MutableSet<String> = linkedSetOf())
    private var session: RealtimeSession? = null
    private var speeds: RealtimeEventBuffer? = null
    private val tracks = linkedMapOf<String, Track>()
    private val finished = linkedSetOf<String>()
    private var latest: ObservedMusicState? = null
    private val sessionFlags = linkedSetOf<String>()

    fun input(value: RealtimeObservation) {
        val time = value.observedMs
        if (value.kind == ObservationKind.START) {
            if (time == null || time < 0 || time > clockMs()) return
            if (value.session == session) return
            abandon("session_replaced_without_stop")
            session = value.session
            sessionFlags.clear(); finished.clear(); latest = null
            speeds = RealtimeEventBuffer(value.session, clockMs, speedCapacity, 2, retentionMs = 7_200_000)
            speeds!!.offer(value)
            return
        }
        if (value.session != session) return
        if (time == null || time < 0 || time > clockMs()) {
            sessionFlags += "invalid_or_future_time"; return
        }
        if (value.kind == ObservationKind.STOP) {
            abandon("collection_stopped_without_listening_summary")
            speeds?.close(); speeds = null; session = null; latest = null
            return
        }
        if (value.kind == ObservationKind.SPEED) {
            val result = speeds?.offer(value)
            if (result != OfferResult.ACCEPTED) sessionFlags += "speed_input_rejected"
            return
        }
        if (value.kind != ObservationKind.MUSIC) return
        val id = value.playbackSessionId ?: return
        if (id in finished) return
        val track = track(id, value.musicId) ?: return
        if (track.music != value.musicId) track.flags += "music_id_mismatch"
        if (latest?.observedMs?.let { time < it } == true) sessionFlags += "music_time_reversal"
        latest = ObservedMusicState(value.session, id, value.musicId, time, value.receivedAt,
            value.label ?: "unknown", value.transitionReason, emptySet())
        when (value.label) {
            "STARTED" -> if (track.start == null) { track.start = time; track.startReceivedAt = value.receivedAt }
            "SKIPPED", "STOPPED", "COMPLETED", "AUTO_TRANSITION" -> {
                track.end = time; track.reason = value.label; track.transition = value.transitionReason
                track.endReceivedAt = value.receivedAt
            }
        }
    }

    fun markLoss(reason: String, key: RealtimeSession? = null) {
        if (key == null || key == session) sessionFlags += reason
    }

    /** Called after the original journal submission, with the exact existing tracker fields. */
    fun listening(key: RealtimeSession, kind: String, fields: Map<String, Any?>) {
        if (key != session) return
        val id = fields["playbackSessionId"] as? String ?: return
        if (id in finished) return
        val t = track(id, fields["musicId"] as? String) ?: return
        if (fields["musicId"] != null && fields["musicId"] != t.music) t.flags += "music_id_mismatch"
        if (kind == "listening_interval") {
            val index = fields.long("intervalIndex") ?: return
            if (index <= t.lastInterval) { t.flags += "duplicate_or_reversed_interval"; return }
            if (index != t.lastInterval + 1) t.flags += "missing_interval"
            t.lastInterval = index; t.intervalCount++
            val from = fields.long("fromElapsedMs")
            val to = fields.long("toElapsedMs")
            val credit = fields.number("creditedWallMs")
            if (from == null || to == null || credit == null || from < 0 || to < from ||
                to > clockMs() || from < t.lastEnd || credit < 0 || credit > to - from + 0.001 ||
                to == from && credit > 0) {
                t.flags += "invalid_interval_time"; return
            }
            t.lastEnd = to
            if (credit == 0.0) return // paused, seek, buffering and unobserved intervals never add context
            t.credited += credit
            val start = t.start
            if (start == null || from < start || t.end?.let { to > it } == true) {
                t.flags += "interval_outside_started_playback"; return
            }
            if (tracks.values.sumOf { it.windows.size } >= 8192) t.flags += "credit_window_capacity_exceeded"
            else t.windows += DrivingCreditWindow(from, to, credit)
        } else if (kind == "listening_summary") {
            val end = fields.long("closedAtElapsedMs")
            val reason = fields["closeReason"] as? String
            // Collection stop is a real boundary, not a fabricated music COMPLETED event.
            if (t.end == null && reason == "collection_stopped") { t.end = end; t.reason = reason }
            if (end == null || end > clockMs() || t.end == null) t.flags += "missing_terminal_event"
            if (fields.long("intervalCountExpected") != t.intervalCount) t.flags += "interval_count_mismatch"
            val total = fields.number("totalListeningWallMs")
            if (total == null || kotlin.math.abs(total - t.credited) > 0.01) t.flags += "listening_credit_mismatch"
            if (fields["finalSnapshotAvailable"] != "true") t.flags += "missing_final_snapshot"
            output(t, fields)
            tracks.remove(id); finished += id
            if (finished.size > 256) finished.remove(finished.first())
        }
    }

    private fun track(id: String, music: String?): Track? {
        tracks[id]?.let { return it }
        if (tracks.size >= playbackCapacity) { sessionFlags += "playback_capacity_exceeded"; return null }
        return Track(id, music).also { tracks[id] = it }
    }

    private fun abandon(reason: String) {
        tracks.values.forEach { it.flags += reason; output(it, emptyMap()) }
        tracks.clear()
    }

    private fun output(t: Track, summary: Map<String, Any?>) {
        val key = session ?: return
        val flags = (sessionFlags + t.flags).toMutableSet()
        if (t.start == null) flags += "missing_started"
        if (t.end == null) flags += "missing_terminal_event"
        if (t.start != null && t.end != null && t.end!! < t.start!!) flags += "end_before_start"
        val invalidBoundary = t.start == null || t.end == null || t.windows.any {
            it.fromMs < t.start!! || it.toMs > t.end!!
        }
        if (invalidBoundary) flags += "incomplete_playback_boundary"
        // Query once at the confirmed playback end. Both endpoints of every speed pair must
        // have been observed by that end; masks exclude pauses/seeks without losing callback-edge coverage.
        val context = if (!invalidBoundary && t.end!! > t.start!!)
            speeds?.snapshot()?.let { DrivingContextProcessor(windowMs = t.end!! - t.start!!)
                .process(it, t.end!!, t.windows) } else null
        flags += context?.qualityFlags.orEmpty().filter { it in setOf("observation_reversal", "sample_overflow",
            "upstream_loss", "missing_start", "boundary_rejected", "boundary_overflow") }
        val coverage = context?.coverage?.coerceIn(0.0, 1.0) ?: 0.0
        if (context?.quality != ContextQuality.AVAILABLE) flags += "insufficient_or_stale_speed_coverage"
        val usable = flags.isEmpty()
        val mean = if (usable) context?.averageSpeedKmh else null
        emit(PlaybackContextFeatures(linkedMapOf(
            "featureVersion" to "1", "runId" to key.runId, "driveSessionId" to key.driveSessionId,
            "playbackSessionId" to t.id, "musicId" to t.music,
            "playbackStartElapsedMs" to t.start?.toString(), "playbackEndElapsedMs" to t.end?.toString(),
            "durationSec" to summary.number("durationMs")?.div(1000)?.toString(),
            "uniqueListeningRate" to summary["uniqueListeningRate"],
            "uniqueListenedMediaMs" to summary["uniqueListenedMediaMs"],
            "creditedWallMs" to summary["totalListeningWallMs"],
            "listeningConfidence" to summary["confidence"], "listeningQualityFlags" to summary["qualityFlags"],
            "avgSpeedKmh" to mean?.toString(),
            "speedStdKmh" to if (usable) context?.speedStdDevKmh?.toString() else null,
            "stopRatio" to if (usable) context?.stationaryRatio?.toString() else null,
            "brakeEventsPerMin" to null, "steeringChange" to null, "cruiseActiveRatio" to null,
            "unavailableFeatures" to listOf("brakeEventsPerMin", "steeringChange", "cruiseActiveRatio"),
            "observationCoverage" to coverage.toString(), "contextQuality" to if (usable) "available" else "unknown",
            "qualityFlags" to flags.sorted(), "endReason" to t.reason, "transitionReason" to t.transition,
            "source" to "AAOS_callback_speed+MEDIA3_ListeningTracker",
            "timeBasis" to "app_callback_elapsed_realtime", "sourceOccurredAt" to null,
            "creditAllocation" to "uniform_within_existing_tracker_interval",
            "startReceivedAt" to t.startReceivedAt, "endReceivedAt" to t.endReceivedAt,
            "sourceObservationDelayMs" to null,
            "processingDelayMs" to t.end?.let { (clockMs() - it).coerceAtLeast(0).toString() },
            "processedAt" to java.time.Instant.now().toString(),
            "processedElapsedMs" to clockMs().toString(),
        )))
    }

    override fun stateAt(session: RealtimeSession, observedMs: Long): ObservedMusicState? = latest?.takeIf {
        it.session == session && observedMs >= it.observedMs && observedMs - it.observedMs <= 2500
    }

    private fun Map<String, Any?>.long(key: String) = (get(key) as? String)?.toLongOrNull()
    private fun Map<String, Any?>.number(key: String) = (get(key) as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
