package com.example.gleo_type.ui.app

import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

enum class RecommendationOrigin { TEST_CATALOG, RULE_BASED, AI_MODEL }
enum class MusicScope(val label: String) { THREE("3곡"), SAVED("저장곡"), ALL("전체") }
enum class RecommendationPhase { LOADING, READY, EMPTY, ERROR }
data class UsedFeature(val name: String, val source: String, val observedAt: String?)
data class RecommendationRun(val id: String, val createdAt: String, val catalogObservedAt: String?,
    val origin: RecommendationOrigin, val algorithm: String, val modelVersion: String?, val scope: MusicScope,
    val variant: Int, val musicIds: List<String>, val catalogFingerprint: String,
    val usedFeatures: List<UsedFeature>, val driveId: String?, val scores: Map<String, Double> = emptyMap(),
    val drivingDecision: com.example.gleo_type.recommendation.DrivingDecision? = null,
    val fusionDecision: com.example.gleo_type.recommendation.FusionDecision? = null,
    val contextEvidence:String? = null)

/** Local static suggestions, not an AI or a claim about acoustic mood/driving context. */
object LocalRecommendationBuilder {
    fun build(catalog: List<Track>, scope: MusicScope, savedIds: Set<String>, excludedIds: Set<String>,
        variant: Int, catalogObservedAt: String?, driveId: String?, at: String = Instant.now().toString()): RecommendationRun {
        require(catalog.map { it.id }.distinct().size == catalog.size) { "Duplicate catalog ID" }
        val base = LocalPreviewRecommendations().recommendations(catalog)
        val eligible = when (scope) {
            MusicScope.THREE -> base.take(3)
            MusicScope.SAVED -> base.filter { it.id in savedIds }
            MusicScope.ALL -> base
        }.filterNot { it.id in excludedIds }
        val shift = if (eligible.isEmpty()) 0 else variant.mod(eligible.size)
        val tracks = eligible.drop(shift) + eligible.take(shift)
        val digest = MessageDigest.getInstance("SHA-256").digest(catalog.map { it.id }.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val features = mutableListOf(UsedFeature("musicId · stable ID order", "own MediaLibraryService catalog", catalogObservedAt),
            UsedFeature("scope:${scope.name}", "explicit UI selection", at))
        if (scope == MusicScope.SAVED) features += UsedFeature("saved IDs: ${savedIds.sorted().joinToString()}", "local pin! snapshot", at)
        if (excludedIds.isNotEmpty()) features += UsedFeature("excluded IDs: ${excludedIds.sorted().joinToString()}", "explicit UI preference snapshot", at)
        return RecommendationRun(UUID.randomUUID().toString(), at, catalogObservedAt,
            RecommendationOrigin.TEST_CATALOG, "local_preview_rotation_v1", null, scope, variant, tracks.map { it.id }, digest, features, driveId)
    }
}

enum class DataOperation { PLAY, SKIP, REORDER_TEST_LIST, EXCLUDE_SUGGESTION, CLEAR_LISTENING_HISTORY, RESET_PROFILE }
object VerificationPolicy {
    fun requiresConfirmation(operation: DataOperation) = operation in setOf(DataOperation.CLEAR_LISTENING_HISTORY, DataOperation.RESET_PROFILE)
}

/** Future attachments are local metadata references. No picker/upload/model ingestion exists yet. */
data class ArchiveAttachment(val id: String, val kind: String, val localReference: String,
    val label: String, val mimeType: String? = null) {
    init { require(kind in setOf("photo", "document", "other")); require(localReference.startsWith("content://") || localReference.startsWith("file://")) }
}
