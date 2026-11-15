package com.example.gleo_type.event.music

import kotlin.math.max
import kotlin.math.min

/** Pure callback-observation accounting, not proof of audibility or human attention. */
class ListeningTracker(
    private val write: (String, String, String, Map<String, Any?>) -> Unit,
    private val maxGapMs: Long = 2500,
    private val positionToleranceMs: Long = 250
) {
    data class Sample(val driveId: String, val playbackId: String, val musicId: String,
        val elapsedMs: Long, val receivedAt: String, val positionMs: Long?, val durationMs: Long?,
        val isPlaying: Boolean, val speed: Double, val reason: String,
        val positionBasis: String = "player_snapshot", val playerState: String? = null)
    private data class State(val first: Sample, var last: Sample? = null, var duration: Long? = null,
        var wall: Double = 0.0, var media: Double = 0.0, var intervals: Int = 0,
        var observations: Int = 0, val ranges: MutableList<Pair<Double, Double>> = mutableListOf(),
        val flags: MutableSet<String> = linkedSetOf(), var closed: Boolean = false)
    private val states = linkedMapOf<Pair<String, String>, State>()
    private var active: Pair<String, String>? = null
    private fun key(s: Sample) = s.driveId to s.playbackId

    @Synchronized fun observe(sample: Sample) {
        val k = key(sample)
        if (states[k]?.closed == true) return // A stale group must not finalize a newer active group.
        active?.takeIf { it != k }?.let {
            val old = states.getValue(it)
            old.flags += "identity_changed_without_boundary"
            finish(it, sample.elapsedMs, sample.receivedAt, "identity_changed_without_boundary", false)
        }
        val state = states.getOrPut(k) { State(sample) }
        if (state.closed) return // Late callbacks cannot reopen a finalized drive/playback group.
        check(state.first.musicId == sample.musicId) { "Playback ID reused for a different track" }
        sample.durationMs?.takeIf { it > 0 }?.let { state.duration = it }
        state.last?.let { interval(state, it, sample) }
        state.last = sample
        active = k
        state.observations++
        write("listening_observation", sample.driveId, sample.receivedAt, common(state) + mapOf(
            "observationIndex" to state.observations.toString(), "receivedElapsedMs" to sample.elapsedMs.toString(),
            "receivedAt" to sample.receivedAt,
            "observedPositionMs" to sample.positionMs?.toString(), "observedDurationMs" to sample.durationMs?.toString(),
            "observedIsPlaying" to sample.isPlaying.toString(), "playbackSpeed" to sample.speed.toString(),
            "observationReason" to sample.reason, "positionBasis" to sample.positionBasis,
            "playerState" to sample.playerState))
    }

    /** Close using oldPosition, never the post-seek position or normalized COMPLETED duration. */
    @Synchronized fun discontinuity(playbackId: String?, oldPositionMs: Long, elapsedMs: Long, at: String, reason: String) {
        val k = active ?: return
        if (k.second != playbackId) return
        val state = states.getValue(k)
        val last = state.last ?: return
        observe(last.copy(elapsedMs = elapsedMs, receivedAt = at, positionMs = oldPositionMs,
            isPlaying = false, reason = "discontinuity_before:$reason", positionBasis = "position_info_old"))
        state.last = null // The next anchor starts at newPosition, with zero time bridging the seek.
    }

    @Synchronized fun finishPlayback(playbackId: String?, elapsedMs: Long, at: String, reason: String) {
        val k = active ?: return
        if (k.second == playbackId) finish(k, elapsedMs, at, reason, true)
    }

    @Synchronized fun finishCollection(driveId: String, elapsedMs: Long, at: String, hasFinalSnapshot: Boolean) {
        states.filter { it.key.first == driveId && !it.value.closed }.keys.toList().forEach {
            finish(it, elapsedMs, at, "collection_stopped", hasFinalSnapshot)
        }
    }

    private fun interval(state: State, from: Sample, to: Sample) {
        val dt = to.elapsedMs - from.elapsedMs
        if (dt == 0L && from.positionMs == to.positionMs) return
        val flags = linkedSetOf<String>()
        var credited = 0.0
        val p0 = from.positionMs
        val p1 = to.positionMs
        val speed = from.speed
        when {
            dt < 0 -> flags += "monotonic_clock_regression"
            !from.isPlaying -> flags += "not_playing"
            dt > maxGapMs -> flags += "observation_gap"
            p0 == null || p1 == null -> flags += "missing_position"
            !speed.isFinite() || speed <= 0 -> flags += "invalid_playback_speed"
            p1 < p0 -> flags += "unreported_backward_discontinuity"
            p1 - p0 > dt * speed + positionToleranceMs -> flags += "unreported_forward_discontinuity"
            else -> {
                val duration = state.duration?.toDouble()
                val start = max(0.0, p0.toDouble()).let { if (duration != null) min(it, duration) else it }
                val end = max(start, p1.toDouble()).let { if (duration != null) min(it, duration) else it }
                credited = max(0.0, min(end - start, dt * speed))
                if (credited > 0) {
                    val merged = union(state.ranges + (start to (start + credited)))
                    state.ranges.clear(); state.ranges.addAll(merged)
                }
                if (credited == 0.0 && dt > 0) flags += "position_stalled"
                if (end - start > dt * speed) flags += "progress_capped_by_monotonic_time"
            }
        }
        state.intervals++
        state.media += credited
        val wall = if (credited > 0) credited / speed else 0.0
        state.wall += wall
        state.flags += flags.filter { it != "not_playing" }
        write("listening_interval", from.driveId, to.receivedAt, common(state) + mapOf(
            "intervalIndex" to state.intervals.toString(), "fromElapsedMs" to from.elapsedMs.toString(),
            "toElapsedMs" to to.elapsedMs.toString(), "elapsedMs" to dt.toString(),
            "fromReceivedAt" to from.receivedAt, "toReceivedAt" to to.receivedAt,
            "fromPositionMs" to p0?.toString(), "toPositionMs" to p1?.toString(),
            "creditedMediaFromMs" to if (credited > 0) max(0.0, p0!!.toDouble()).toString() else null,
            "creditedMediaToMs" to if (credited > 0) (max(0.0, p0!!.toDouble()) + credited).toString() else null,
            "creditedMediaMs" to credited.toString(), "creditedWallMs" to wall.toString(),
            "wasPlaying" to from.isPlaying.toString(), "playbackSpeed" to speed.toString(),
            "closeReason" to to.reason, "endpointBasis" to to.positionBasis,
            "qualityFlags" to flags.toList(), "confidence" to
                if (credited > 0) "bounded_callback_estimate" else "no_listening_credit"))
    }

    private fun finish(k: Pair<String, String>, elapsedMs: Long, at: String, reason: String, finalSnapshot: Boolean) {
        val state = states.getValue(k)
        if (state.closed) return
        state.closed = true
        if (!finalSnapshot) state.flags += "missing_final_snapshot"
        val tail = state.last?.let { max(0, elapsedMs - it.elapsedMs) } ?: 0
        if (tail > 0) state.flags += "unobserved_tail_excluded"
        val ranges = union(state.ranges, state.duration)
        val unique = ranges.sumOf { it.second - it.first }
        val duration = state.duration
        write("listening_summary", k.first, at, common(state) + mapOf(
            "closeReason" to reason, "closedAtElapsedMs" to elapsedMs.toString(),
            "finalSnapshotAvailable" to finalSnapshot.toString(), "unobservedTailMs" to tail.toString(),
            "intervalCountExpected" to state.intervals.toString(), "observationCountExpected" to state.observations.toString(),
            "totalListeningWallMs" to state.wall.toString(), "totalListenedMediaMs" to state.media.toString(),
            "uniqueListenedMediaMs" to unique.toString(),
            "uniqueListeningRate" to duration?.let { min(1.0, unique / it).toString() },
            "listenedRangesMs" to ranges.map { listOf(it.first.toString(), it.second.toString()) },
            "qualityFlags" to (state.flags + if (duration == null) setOf("unknown_duration") else emptySet()).toList(),
            "confidence" to if (state.flags.any { it != "progress_capped_by_monotonic_time" } || duration == null)
                "incomplete_lower_bound" else "bounded_callback_estimate"))
        if (active == k) active = null
    }

    private fun common(state: State): Map<String, Any?> = mapOf(
        "listeningVersion" to "1", "musicId" to state.first.musicId,
        "playbackSessionId" to state.first.playbackId, "durationMs" to state.duration?.toString(),
        "sourceOccurredAt" to null, "sourceOccurredElapsedMs" to null,
        "sourceClock" to "unknown", "timeBasis" to "callback_elapsed_realtime",
        "measurementMeaning" to "observed_player_progress_not_human_attention")

    companion object {
        fun union(input: List<Pair<Double, Double>>, duration: Long? = null): List<Pair<Double, Double>> {
            val merged = mutableListOf<Pair<Double, Double>>()
            input.map { (a, b) -> max(0.0, a) to min(b, duration?.toDouble() ?: b) }
                .filter { it.second > it.first }.sortedBy { it.first }.forEach { range ->
                    val last = merged.lastOrNull()
                    if (last != null && range.first <= last.second) merged[merged.lastIndex] = last.first to max(last.second, range.second)
                    else merged += range
                }
            return merged
        }
    }
}
