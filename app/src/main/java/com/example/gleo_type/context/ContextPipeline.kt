package com.example.gleo_type.context

import com.example.gleo_type.recommendation.RecommendationModelAdapter
import com.example.gleo_type.ui.app.*
import java.util.UUID

data class RecommendationPlan(val recommendationId: String, val snapshot: ContextSnapshot, val run: RecommendationRun)
data class PlaybackTicket(val playbackRequestId: String, val musicId: String)
data class WeakListeningEvidence(val sourceRecordKey: String, val creditedWallMs: Long, val durationMs: Long)

/** IO-only coordinator. Commands, recommendation results, observed playback and feedback are separate facts. */
class ContextPipeline(private val user: String, private val appRun: String, private val drive: () -> String?,
    private val snapshots: ContextSnapshotProvider, val ledger: PipelineLedger, private val elapsed: () -> Long) {
    private fun frozenValue(v: Any?): Any? = when(v) {
        null, org.json.JSONObject.NULL -> null
        is org.json.JSONObject -> frozenMap(v)
        is org.json.JSONArray -> (0 until v.length()).map { frozenValue(v.get(it)) }
        else -> v
    }
    private fun frozenMap(j: org.json.JSONObject): Map<String,Any?> =
        j.keys().asSequence().associateWith { frozenValue(j.get(it)) }
    private fun accept(command: VoiceCommand) {
        require(command.localUserId == user && command.appRunId == appRun && command.driveSessionId == drive())
        require(elapsed() - command.receivedElapsedMs in 0..30_000) { "Stale or future command" }
        require(command.source != CommandSource.OFFICIAL_GLEO_ACTION) { "Official Gleo transport is not verified" }
        ledger.append(LedgerKind.VOICE_REQUEST, CausalIds(user, appRun, command.driveSessionId, command.voiceRequestId),
            command.toMap(), command.voiceRequestId)
    }
    fun recommend(command: VoiceCommand, catalog: List<Track>, model: RecommendationModelAdapter,
        fusion: com.example.gleo_type.recommendation.FusionRuntime? = null,excludeCurrentMusicId:String?=null): RecommendationPlan {
        require(command.intent in setOf(CommandIntent.RECOMMEND_AND_PLAY, CommandIntent.CHANGE_MOOD))
        accept(command)
        val snapshot = snapshots.capture(command.driveSessionId, command)
        val recommendation = UUID.randomUUID().toString()
        val ids = CausalIds(user, appRun, command.driveSessionId, command.voiceRequestId, snapshot.contextSnapshotId, recommendation)
        ledger.append(LedgerKind.CONTEXT_SNAPSHOT, ids.copy(recommendationId = null), snapshot.toMap(), snapshot.contextSnapshotId)
        val playable = catalog
        ledger.append(LedgerKind.RECOMMENDATION_REQUEST, ids, mapOf("intent" to command.intent.name,
            "playableMusicIds" to playable.map { it.id }), recommendation)
        val excluded=excludeCurrentMusicId?.let{setOf(it)} ?: emptySet()
        val run = model.recommend(playable, MusicScope.THREE, emptySet(), excluded, 0, null,
            command.driveSessionId, command.musicRequest, snapshot = snapshot, fusion = fusion, utterance = command.text)
        require(drive() == command.driveSessionId) { "Drive changed during recommendation" }
        ledger.append(LedgerKind.RECOMMENDATION_RESULT, ids, mapOf("engineRunId" to run.id,
            "origin" to run.origin.name, "algorithm" to run.algorithm, "modelVersion" to run.modelVersion,
            "musicIds" to run.musicIds, "scores" to run.scores,
            "speedCorrectionApplied" to (run.drivingDecision?.applied == true),
            "experimentalFusion" to run.fusionDecision?.toMap(),
            "frozenEvidence" to run.contextEvidence?.let { frozenMap(org.json.JSONObject(it)) },
            "usedFeatures" to run.usedFeatures.map { mapOf("name" to it.name, "source" to it.source, "observedAt" to it.observedAt) }))
        return RecommendationPlan(recommendation, snapshot, run)
    }
    fun preparePlayback(recommendationId: String, musicId: String): PlaybackTicket {
        val result = ledger.records().single { it.kind == LedgerKind.RECOMMENDATION_RESULT && it.ids.recommendationId == recommendationId }
        require(result.ids.localUserId == user && result.ids.appRunId == appRun && result.ids.driveSessionId == drive())
        require(result.payload.getString("origin") == RecommendationOrigin.AI_MODEL.name) { "Preview is not AI playback" }
        val id = UUID.randomUUID().toString()
        ledger.append(LedgerKind.PLAYBACK_REQUEST, result.ids.copy(musicId = musicId, playbackRequestId = id),
            mapOf("executionStatus" to "PREPARED_NOT_PLAYED"), id)
        return PlaybackTicket(id, musicId)
    }
    fun validateTicket(ticketId: String, musicId: String): Boolean = runCatching {
        val records = ledger.records()
        val ticket = records.single { it.kind == LedgerKind.PLAYBACK_REQUEST && it.ids.playbackRequestId == ticketId }
        ticket.ids.localUserId == user && ticket.ids.appRunId == appRun && ticket.ids.driveSessionId == drive() &&
            ticket.ids.musicId == musicId && records.none { it.kind == LedgerKind.PLAYBACK_BOUND && it.ids.playbackRequestId == ticketId }
    }.getOrDefault(false)

    /** Called with actual Media3 occurrence ID and drive observed at the callback, not nearest-time lookup. */
    fun bindObservedPlayback(ticketId: String, musicId: String, playbackId: String, observedDrive: String?) {
        val records = ledger.records()
        val ticket = records.single { it.kind == LedgerKind.PLAYBACK_REQUEST && it.ids.playbackRequestId == ticketId }
        require(ticket.ids.localUserId == user && ticket.ids.appRunId == appRun && ticket.ids.driveSessionId == observedDrive && ticket.ids.musicId == musicId)
        val previous = records.singleOrNull { it.kind == LedgerKind.PLAYBACK_BOUND && it.ids.playbackRequestId == ticketId }
        if (previous != null) { require(previous.ids.playbackSessionId == playbackId); return }
        ledger.append(LedgerKind.PLAYBACK_BOUND, ticket.ids.copy(playbackSessionId = playbackId),
            mapOf("basis" to "explicit_media_item_ticket_and_Media3_occurrence", "actualMusicId" to musicId), playbackId)
    }
    private fun binding(playbackId: String, musicId: String): PipelineRecord = ledger.records()
        .single { it.kind == LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId == playbackId }.also {
            require(it.ids.localUserId == user && it.ids.appRunId == appRun && it.ids.driveSessionId == drive() && it.ids.musicId == musicId)
        }
    fun commandExecuted(command: VoiceCommand, playbackId: String, musicId: String, executionStatus: String) {
        require(command.intent !in setOf(CommandIntent.RECOMMEND_AND_PLAY, CommandIntent.CHANGE_MOOD))
        require(executionStatus in setOf("SUCCESS", "FAILED", "UNSUPPORTED"))
        val target = binding(playbackId, musicId)
        accept(command)
        ledger.append(LedgerKind.COMMAND_EXECUTION, target.ids, mapOf("triggerVoiceRequestId" to command.voiceRequestId,
            "intent" to command.intent.name, "executionStatus" to executionStatus, "preferenceInferred" to false))
    }
    fun feedback(playbackId: String, musicId: String, kind: FeedbackKind, triggerVoiceRequestId: String? = null,
        weakEvidence: WeakListeningEvidence? = null): String {
        val target = binding(playbackId, musicId)
        if (triggerVoiceRequestId != null) {
            require(ledger.records().none { it.kind == LedgerKind.LISTENING_FEEDBACK &&
                it.payload.optString("triggerVoiceRequestId") == triggerVoiceRequestId }) { "Feedback command already recorded" }
            val execution = ledger.records().single { it.kind == LedgerKind.COMMAND_EXECUTION &&
                it.payload.optString("triggerVoiceRequestId") == triggerVoiceRequestId }
            require(execution.ids == target.ids && execution.payload.getString("executionStatus") == "SUCCESS")
            require(execution.payload.getString("intent") == kind.name) { "Feedback differs from explicit command" }
        }
        if (kind in setOf(FeedbackKind.LIKE, FeedbackKind.DISLIKE, FeedbackKind.PAUSE, FeedbackKind.NEXT))
            require(triggerVoiceRequestId != null) { "Explicit command evidence required" }
        if (kind in setOf(FeedbackKind.SUFFICIENT_LISTEN, FeedbackKind.EARLY_SKIP)) {
            require(weakEvidence != null && weakEvidence.sourceRecordKey.isNotBlank() && weakEvidence.durationMs > 0 &&
                weakEvidence.creditedWallMs in 0..weakEvidence.durationMs)
        }
        val meaning = FeedbackContract.meaning(kind); val id = UUID.randomUUID().toString()
        ledger.append(LedgerKind.LISTENING_FEEDBACK, target.ids.copy(feedbackEventId = id), mapOf(
            "triggerVoiceRequestId" to triggerVoiceRequestId, "kind" to kind.name, "polarity" to meaning.polarity,
            "strength" to meaning.strength, "learningDisposition" to meaning.learningDisposition,
            "weight" to null, "learnedParameter" to false,
            "sourceRecordKey" to weakEvidence?.sourceRecordKey, "creditedWallMs" to weakEvidence?.creditedWallMs,
            "durationMs" to weakEvidence?.durationMs, "weakEvidenceValidation" to "NOT_REVALIDATED_AGAINST_RAW_JOURNAL"), id)
        return id
    }
}
