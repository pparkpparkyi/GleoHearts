package com.example.gleo_type.ui.app

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

interface RecommendationFeedback {
    fun record(action: String, musicId: String, driveId: String?, playbackId: String?, occurredAt: String, callbackElapsedMs: Long)
    fun submit(action: String, musicId: String, driveId: String?, playbackId: String?, occurredAt: String, callbackElapsedMs: Long, failed: (Throwable) -> Unit) {
        runCatching { record(action, musicId, driveId, playbackId, occurredAt, callbackElapsedMs) }.onFailure(failed)
    }
}
object NoFeedback : RecommendationFeedback {
    override fun record(action: String, musicId: String, driveId: String?, playbackId: String?, occurredAt: String, callbackElapsedMs: Long) = Unit
}
/** Explicit UI actions only: never labels a skip/seek as rejection or a pin as a like. */
class LocalRecommendationFeedback(private val directory: File, private val localId: String) : RecommendationFeedback {
    private val feedbackRunId = UUID.randomUUID().toString()
    private var sequence = 0L
    override fun submit(action: String, musicId: String, driveId: String?, playbackId: String?, occurredAt: String, callbackElapsedMs: Long, failed: (Throwable) -> Unit) {
        UiWriteDispatch.submit({ record(action, musicId, driveId, playbackId, occurredAt, callbackElapsedMs) }, failed)
    }
    @Synchronized override fun record(action: String, musicId: String, driveId: String?, playbackId: String?, occurredAt: String, callbackElapsedMs: Long) {
        require(action in setOf("recommendation_excluded", "recommendation_restored"))
        check(directory.isDirectory || directory.mkdirs())
        val next = sequence + 1
        val bytes = (JSONObject().put("version", 1).put("id", UUID.randomUUID().toString())
            .put("feedbackRunId", feedbackRunId).put("sequence", next.toString())
            .put("action", action).put("musicId", musicId).put("localUserId", localId)
            .put("driveSessionId", driveId ?: JSONObject.NULL).put("playbackSessionId", playbackId ?: JSONObject.NULL)
            .put("occurredAt", occurredAt).put("callbackElapsedMs", callbackElapsedMs.toString())
            .put("storedAt", Instant.now().toString()).put("reason", "explicit_ui_action")
            .put("source", "recommendation_card").put("preferenceInference", "none").toString() + "\n").toByteArray(Charsets.UTF_8)
        val target = File(directory, "recommendation-feedback.jsonl")
        check(target.length() + bytes.size <= 8L * 1024 * 1024) { "Feedback storage full; export first" }
        RandomAccessFile(target, "rw").use { out ->
            val original = out.length()
            try { out.seek(original); out.write(bytes); out.fd.sync() }
            catch (e: Exception) { runCatching { out.setLength(original); out.fd.sync() }; throw e }
        }
        sequence = next
    }
}

/** Process-owned FIFO: changing tabs/closing a composable cannot cancel an accepted action. */
internal object UiWriteDispatch {
    fun barrier(): java.util.concurrent.CompletableFuture<Unit> = java.util.concurrent.CompletableFuture<Unit>().also { future ->
        submit({ future.complete(Unit) }, { future.completeExceptionally(it) })
    }
    private data class Work(val action: () -> Unit, val failed: (Throwable) -> Unit)
    private val queue = Channel<Work>(256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init { scope.launch { for (work in queue) runCatching(work.action).onFailure {
        android.util.Log.e("UiFeedback", "[WRITE_FAILED]", it); runCatching { work.failed(it) }
    } } }
    fun submit(action: () -> Unit, failed: (Throwable) -> Unit) {
        if (queue.trySend(Work(action, failed)).isFailure) {
            val error = IllegalStateException("UI write queue full")
            android.util.Log.e("UiFeedback", "[QUEUE_FULL]", error); failed(error)
        }
    }
}
