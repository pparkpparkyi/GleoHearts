package com.example.gleo_type.context

import com.example.gleo_type.recommendation.MusicRequest
import com.example.gleo_type.recommendation.MusicRequestParser
import java.util.UUID

enum class CommandIntent { RECOMMEND_AND_PLAY, CHANGE_MOOD, PLAY, PAUSE, NEXT, PREVIOUS, LIKE, DISLIKE }
enum class CommandSource { TEST_TEXT, SIMULATED_GLEO_ACTION, OFFICIAL_GLEO_ACTION }
data class VoiceCommand(val voiceRequestId: String, val localUserId: String, val appRunId: String,
    val driveSessionId: String?, val intent: CommandIntent, val source: CommandSource,
    val text: String?, val receivedAt: String, val receivedElapsedMs: Long,
    val musicRequest: MusicRequest? = null, val userClaims: Map<String, String> = emptyMap()) {
    fun toMap(): Map<String, Any?> = mapOf("intent" to intent.name, "source" to source.name,
        "text" to text, "receivedAt" to receivedAt, "receivedElapsedMs" to receivedElapsedMs,
        "musicIntent" to musicRequest?.intent?.name, "musicRequestSource" to musicRequest?.source?.name,
        "parserStatus" to musicRequest?.status, "userClaims" to userClaims,
        "officialGleoVerified" to false)
    companion object {
        fun testText(user: String, run: String, drive: String?, intent: CommandIntent, text: String,
            at: String, elapsed: Long, claims: Map<String, String> = emptyMap()) =
            VoiceCommand(UUID.randomUUID().toString(), user, run, drive, intent, CommandSource.TEST_TEXT,
                text, at, elapsed, MusicRequestParser.parse(text), claims)
    }
}

/** IDs are explicit references, never timestamp-nearest joins. voiceRequestId is the originating recommendation command. */
data class CausalIds(val localUserId: String, val appRunId: String, val driveSessionId: String?,
    val voiceRequestId: String, val contextSnapshotId: String? = null, val recommendationId: String? = null,
    val musicId: String? = null, val playbackRequestId: String? = null, val playbackSessionId: String? = null,
    val feedbackEventId: String? = null) {
    fun toMap(): Map<String, Any?> = mapOf("localUserId" to localUserId, "appRunId" to appRunId,
        "driveSessionId" to driveSessionId, "voiceRequestId" to voiceRequestId, "contextSnapshotId" to contextSnapshotId,
        "recommendationId" to recommendationId, "musicId" to musicId, "playbackRequestId" to playbackRequestId,
        "playbackSessionId" to playbackSessionId, "feedbackEventId" to feedbackEventId)
}
enum class FeedbackKind { LIKE, DISLIKE, SUFFICIENT_LISTEN, EARLY_SKIP, PAUSE, NEXT, APP_EXIT, PLAYBACK_ERROR }
data class FeedbackMeaning(val polarity: String, val strength: String, val learningDisposition: String = "CONTRACT_ONLY_NOT_APPLIED")
object FeedbackContract {
    fun meaning(kind: FeedbackKind): FeedbackMeaning = when (kind) {
        FeedbackKind.LIKE -> FeedbackMeaning("positive", "explicit")
        FeedbackKind.DISLIKE -> FeedbackMeaning("negative", "explicit")
        FeedbackKind.SUFFICIENT_LISTEN -> FeedbackMeaning("positive_candidate", "weak_requires_listening_policy")
        FeedbackKind.EARLY_SKIP -> FeedbackMeaning("negative_candidate", "weak_requires_listening_policy")
        else -> FeedbackMeaning("neutral_or_unknown", "none")
    }
}

/** Extension ports, intentionally unavailable. No new model weights or invented cases. */
interface ClapTextEncoderPort { val availability: String; fun encode(command: VoiceCommand): FloatArray? }
interface ExperienceRetrieverPort { val availability: String; fun retrieve(snapshot: ContextSnapshot): List<String> }
interface GleoActionContractPort {
    val registrationStatus: String
    fun receiveValidatedAction(): VoiceCommand?
    fun executionCallback(voiceRequestId: String, executionStatus: String, message: String)
}
