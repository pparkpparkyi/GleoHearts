package com.example.gleo_type.context

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class LedgerKind { VOICE_REQUEST, CONTEXT_SNAPSHOT, RECOMMENDATION_REQUEST, RECOMMENDATION_RESULT,
    PLAYBACK_REQUEST, PLAYBACK_BOUND, COMMAND_EXECUTION, LISTENING_FEEDBACK }
data class PipelineRecord(val recordId: String, val kind: LedgerKind, val ids: CausalIds,
    val payload: JSONObject, val recordedAt: String, val schemaVersion: Int = 1) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", schemaVersion).put("recordId", recordId)
        .put("kind", kind.name).put("ids", JSONObject(ids.toMap())).put("recordedAt", recordedAt).put("payload", payload)
    companion object {
        fun read(j: JSONObject): PipelineRecord {
            require(j.getInt("schemaVersion") == 1) { "Unsupported pipeline ledger version" }
            val i = j.getJSONObject("ids")
            fun optional(key: String) = if (i.isNull(key)) null else i.getString(key)
            return PipelineRecord(j.getString("recordId"), LedgerKind.valueOf(j.getString("kind")),
                CausalIds(i.getString("localUserId"), i.getString("appRunId"), optional("driveSessionId"),
                    i.getString("voiceRequestId"), optional("contextSnapshotId"), optional("recommendationId"),
                    optional("musicId"), optional("playbackRequestId"), optional("playbackSessionId"), optional("feedbackEventId")),
                j.getJSONObject("payload"), j.getString("recordedAt"))
        }
    }
}

/** Separate v1 JSONL, no legacy event/Hearts schema changes. All disk calls belong on an IO worker. */
class PipelineLedger(private val file: File) {
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    fun records(): List<PipelineRecord> = synchronized(lock) { readChecked() }
    fun append(kind: LedgerKind, ids: CausalIds, payload: Map<String, Any?>,
        recordId: String = UUID.randomUUID().toString()): PipelineRecord = synchronized(lock) {
        val previous = readChecked()
        val next = PipelineRecord(recordId, kind, ids, JSONObject(payload), Instant.now().toString())
        validate(previous, next)
        val bytes = (next.json().toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "Pipeline record too large" }
        require(file.length() + bytes.size <= 32L * 1024 * 1024) { "Pipeline ledger limit reached" }
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        FileOutputStream(file, true).use { it.write(bytes); it.fd.sync() }
        next
    }
    private fun readChecked(): List<PipelineRecord> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 32L * 1024 * 1024) { "Pipeline ledger too large" }
        val bytes = file.readBytes()
        require(bytes.isEmpty() || bytes.last() == 10.toByte()) { "Incomplete ledger tail; preserve original" }
        val records = mutableListOf<PipelineRecord>()
        bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach {
            val r = PipelineRecord.read(JSONObject(it)); validate(records, r); records += r
        }
        return records
    }
    private fun validate(previous: List<PipelineRecord>, row: PipelineRecord) {
        val id = row.ids
        require(row.schemaVersion == 1 && row.recordId.isNotBlank() && previous.none { it.recordId == row.recordId })
        require(id.localUserId.isNotBlank() && id.appRunId.isNotBlank() && id.voiceRequestId.isNotBlank())
        fun sameRoot(parent: PipelineRecord) {
            require(parent.ids.localUserId == id.localUserId && parent.ids.appRunId == id.appRunId &&
                parent.ids.driveSessionId == id.driveSessionId && parent.ids.voiceRequestId == id.voiceRequestId) { "Cross-user/run/drive/voice link" }
        }
        fun parent(kind: LedgerKind, predicate: (CausalIds) -> Boolean): PipelineRecord =
            previous.singleOrNull { it.kind == kind && predicate(it.ids) }?.also(::sameRoot)
                ?: error("Missing or ambiguous causal parent: $kind")
        if (row.kind == LedgerKind.VOICE_REQUEST) {
            require(previous.none { it.kind == row.kind && it.ids.voiceRequestId == id.voiceRequestId }); return
        }
        parent(LedgerKind.VOICE_REQUEST) { it.voiceRequestId == id.voiceRequestId }
        when (row.kind) {
            LedgerKind.CONTEXT_SNAPSHOT -> {
                require(id.contextSnapshotId != null)
                require(previous.none { it.kind == row.kind && it.ids.contextSnapshotId == id.contextSnapshotId })
            }
            LedgerKind.RECOMMENDATION_REQUEST -> {
                require(id.recommendationId != null)
                parent(LedgerKind.CONTEXT_SNAPSHOT) { it.contextSnapshotId == id.contextSnapshotId }
                require(previous.none { it.kind == row.kind && it.ids.recommendationId == id.recommendationId })
            }
            LedgerKind.RECOMMENDATION_RESULT -> {
                val p = parent(LedgerKind.RECOMMENDATION_REQUEST) { it.recommendationId == id.recommendationId }
                require(p.ids.contextSnapshotId == id.contextSnapshotId)
                require(previous.none { it.kind == row.kind && it.ids.recommendationId == id.recommendationId })
            }
            LedgerKind.PLAYBACK_REQUEST -> {
                val p = parent(LedgerKind.RECOMMENDATION_RESULT) { it.recommendationId == id.recommendationId }
                require(id.playbackRequestId != null && id.musicId != null && p.ids.contextSnapshotId == id.contextSnapshotId)
                val tracks = p.payload.getJSONArray("musicIds")
                require((0 until tracks.length()).any { tracks.getString(it) == id.musicId }) { "Track not in recommendation" }
                require(previous.none { it.kind == row.kind && it.ids.playbackRequestId == id.playbackRequestId })
            }
            LedgerKind.PLAYBACK_BOUND -> {
                val p = parent(LedgerKind.PLAYBACK_REQUEST) { it.playbackRequestId == id.playbackRequestId }
                require(p.ids.copy(playbackSessionId = id.playbackSessionId) == id && id.playbackSessionId != null)
                require(previous.none { it.kind == row.kind && (it.ids.playbackRequestId == id.playbackRequestId || it.ids.playbackSessionId == id.playbackSessionId) })
            }
            LedgerKind.COMMAND_EXECUTION, LedgerKind.LISTENING_FEEDBACK -> {
                val p = parent(LedgerKind.PLAYBACK_BOUND) { it.playbackSessionId == id.playbackSessionId }
                require(p.ids.copy(feedbackEventId = id.feedbackEventId) == id) { "Feedback target differs from observed playback" }
                if (row.kind == LedgerKind.LISTENING_FEEDBACK) {
                    require(id.feedbackEventId != null)
                    require(previous.none { it.ids.feedbackEventId == id.feedbackEventId })
                }
                val trigger = row.payload.optString("triggerVoiceRequestId").takeIf { it.isNotBlank() }
                if (trigger != null) {
                    val v = previous.singleOrNull { it.kind == LedgerKind.VOICE_REQUEST && it.ids.voiceRequestId == trigger }
                        ?: error("Missing trigger command")
                    require(v.ids.localUserId == id.localUserId && v.ids.appRunId == id.appRunId && v.ids.driveSessionId == id.driveSessionId)
                }
            }
            else -> error("Unexpected ledger kind")
        }
    }
}
