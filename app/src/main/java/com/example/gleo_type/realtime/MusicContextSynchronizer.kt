package com.example.gleo_type.realtime

/** Adapter contract implemented by PlaybackContextAggregator. Unknown or stale state returns null. */
fun interface MusicContextSynchronizer {
    fun stateAt(session: RealtimeSession, observedMs: Long): ObservedMusicState?
}

data class ObservedMusicState(
    val session: RealtimeSession,
    val playbackSessionId: String?,
    val musicId: String?,
    val observedMs: Long,
    val receivedAt: String?,
    val label: String,
    val transitionReason: String?,
    val qualityFlags: Set<String>,
)
