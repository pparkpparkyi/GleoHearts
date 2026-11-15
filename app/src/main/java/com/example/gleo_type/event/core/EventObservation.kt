package com.example.gleo_type.event.core

/** Journal-only context. Never added to the legacy serialized event object. */
data class EventObservation(
    val receivedAt: String,
    val receivedElapsedMs: Long?,
    val playbackSessionId: String? = null,
    val transitionReason: String = "unknown",
    val actionOrigin: String = "unknown",
    val positionBasis: String = "estimated_summary",
    val observedPositionMs: Long? = null,
    val playbackSpeed: Float? = null,
    val observedIsPlaying: Boolean? = null,
    val observedDurationMs: Long? = null,
    val observedStateBasis: String? = null
) {
    fun fields(): Map<String, Any?> = mapOf(
        "receivedAt" to receivedAt, "receivedElapsedMs" to receivedElapsedMs?.toString(),
        "playbackSessionId" to playbackSessionId,
        "sourceOccurredAt" to null, "sourceOccurredElapsedMs" to null,
        "timeBasis" to "app_callback_observation", "transitionReason" to transitionReason,
        "actionOrigin" to actionOrigin, "positionBasis" to positionBasis,
        "observedPositionMs" to observedPositionMs?.toString(),
        "playbackSpeed" to playbackSpeed?.toString(),
        "observedIsPlaying" to observedIsPlaying?.toString(),
        "observedDurationMs" to observedDurationMs?.toString(),
        "observedStateBasis" to observedStateBasis
    )
}
