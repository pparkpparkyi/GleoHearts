package com.example.gleo_type.event.music

import com.example.gleo_type.event.core.MusicLabel
import java.util.UUID

/** A new item occurrence/repeat/restart gets a new ID. Pause/seek keep the ID. */
class PlaybackSessions(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private var id: String? = null
    @Synchronized fun currentId(): String? = id
    @Synchronized fun begin(): String = newId().also { id = it }
    @Synchronized fun clear() { id = null }
}

/** Evidence of an app UI command, not evidence of preference or rejection. */
class MusicActionEvidence(private val now: () -> Long) {
    private data class Action(val name: String, val playbackId: String?, val at: Long)
    private var pending: Action? = null
    @Synchronized fun mark(name: String, playbackId: String?) {
        pending = Action(name, playbackId, now())
    }
    @Synchronized fun consume(names: Set<String>, playbackId: String?): String? {
        val action = pending ?: return null
        if (now() - action.at !in 0..2_000 || action.playbackId != playbackId) {
            pending = null
            return null
        }
        if (action.name !in names) return null
        pending = null
        return action.name
    }
}

/** The Media3 seek transition alone does not establish a manual skip. */
object MusicTransitionClassifier {
    fun label(reason: String, confirmedAction: String?): MusicLabel = when (reason) {
        "auto", "repeat" -> MusicLabel.AUTO_TRANSITION
        "seek" -> if (confirmedAction in setOf("next_button", "previous_button"))
            MusicLabel.SKIPPED else MusicLabel.UNKNOWN_TRANSITION
        else -> MusicLabel.UNKNOWN_TRANSITION
    }
}
