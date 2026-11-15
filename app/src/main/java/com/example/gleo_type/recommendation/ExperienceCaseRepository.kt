package com.example.gleo_type.recommendation

import com.example.gleo_type.context.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Separate append-only case revisions. No Hearts/legacy JSONL migration, edits or timestamp joins. IO only. */
class ExperienceCaseRepository(private val file: File, private val user: String) {
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }
    fun cases(): List<ExperienceCase> = synchronized(lock) { read() }
    private fun read(): List<ExperienceCase> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 16L*1024*1024)
        val bytes=file.readBytes()
        require(bytes.isEmpty() || bytes.last()==10.toByte()) { "Incomplete case journal; preserved" }
        val latest=linkedMapOf<String,ExperienceCase>()
        bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val j=JSONObject(line);require(j.getInt("schemaVersion")==1 && j.getString("localUserId")==user)
            val f=j.getJSONObject("features")
            val features=f.keys().asSequence().associateWith { k -> f.getJSONObject(k).let { facet ->
                facet.keys().asSequence().associateWith { facet.getString(it) } } }
            val ids=j.getJSONArray("feedbackEventIds")
            val c=ExperienceCase(j.getString("caseId"),user,j.getString("musicId"),j.getString("playbackSessionId"),
                ObservedFeatures(features),j.getDouble("reward"),(0 until ids.length()).map { ids.getString(it) }.toSet(),
                j.getLong("updatedAtEpochMs"),j.getString("origin"),
                j.optString("contextSnapshotId").takeIf { it.isNotBlank() && it!="null" },
                j.optString("recommendationId").takeIf { it.isNotBlank() && it!="null" },
                j.optString("driveSessionId").takeIf { it.isNotBlank() && it!="null" })
            require(c.reward.isFinite() && c.reward in -1.0..1.0 && c.feedbackEventIds.isNotEmpty())
            val prior=latest[c.caseId]
            require(prior==null || (prior.musicId==c.musicId && prior.playbackSessionId==c.playbackSessionId &&
                c.feedbackEventIds.containsAll(prior.feedbackEventIds)))
            latest[c.caseId]=c
        }
        return latest.values.toList()
    }
    /** Only an explicit, successfully executed command bound to an actual playback may revise/retain. */
    fun retainExplicit(ledger: PipelineLedger, feedbackId: String, snapshot: ContextSnapshot,
        model: MusicEmbeddingRepository, nowEpochMs: Long): Boolean = synchronized(lock) {
        val rows=ledger.records()
        val feedback=rows.single { it.kind==LedgerKind.LISTENING_FEEDBACK && it.ids.feedbackEventId==feedbackId }
        val ids=feedback.ids
        require(ids.localUserId==user && snapshot.localUserId==user && snapshot.contextSnapshotId==ids.contextSnapshotId &&
            snapshot.appRunId==ids.appRunId && snapshot.driveSessionId==ids.driveSessionId && snapshot.voiceRequestId==ids.voiceRequestId)
        val bound=rows.single { it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==ids.playbackSessionId }
        require(bound.ids==ids.copy(feedbackEventId=null))
        // Only the recommendation's frozen, verified mapping can resolve a Media3 alias.
        val result=rows.single { it.kind==LedgerKind.RECOMMENDATION_RESULT && it.ids.recommendationId==ids.recommendationId }
        val frozen=result.payload.optJSONObject("frozenEvidence")
        val modelId=if(frozen==null) ids.musicId!!.also { require(model.contains(it)) } else {
            val recorded=frozen.getJSONObject("snapshot")
            require(recorded.getString("localUserId")==user && recorded.getString("appRunId")==ids.appRunId &&
                recorded.getString("contextSnapshotId")==ids.contextSnapshotId && recorded.optString("driveSessionId")==ids.driveSessionId)
            val mapping=frozen.getJSONObject("modelToMedia")
            mapping.keys().asSequence().single { mapping.getString(it)==ids.musicId }.also { require(model.contains(it)) }
        }
        val kind=feedback.payload.getString("kind")
        if(kind !in setOf("LIKE","DISLIKE")) return false // PAUSE/NEXT/weak evidence cannot become explicit labels.
        val trigger=feedback.payload.getString("triggerVoiceRequestId")
        val execution=rows.single { it.kind==LedgerKind.COMMAND_EXECUTION && it.payload.optString("triggerVoiceRequestId")==trigger }
        require(execution.ids==bound.ids && execution.payload.getString("executionStatus")=="SUCCESS" &&
            execution.payload.getString("intent")==kind)
        val recordedSnapshot=rows.single { it.kind==LedgerKind.CONTEXT_SNAPSHOT && it.ids.contextSnapshotId==ids.contextSnapshotId }
        // A caller must not replace the recommendation's observed features with a different snapshot carrying the same ID.
        val supplied=JSONObject(snapshot.toMap())
        require(recordedSnapshot.payload.getLong("capturedElapsedMs")==snapshot.capturedElapsedMs &&
            recordedSnapshot.payload.getString("createdAt")==snapshot.createdAt &&
            recordedSnapshot.payload.getString("environment")==snapshot.environment)
        for(key in listOf("currentLocation","destination","driving","road","timeWeather","voice"))
            require(canonical(supplied.getJSONObject(key))==canonical(recordedSnapshot.payload.getJSONObject(key)))
        val cases=read()
        if(cases.any { feedbackId in it.feedbackEventIds }) return false
        val key=sha("${ids.appRunId}/${ids.playbackSessionId}")
        val old=cases.singleOrNull { it.caseId==key }
        val feedbackIds=old?.feedbackEventIds.orEmpty()+feedbackId
        val latestExplicit=rows.last { it.kind==LedgerKind.LISTENING_FEEDBACK && it.ids.feedbackEventId in feedbackIds }
        val reward=if(latestExplicit.payload.getString("kind")=="LIKE")1.0 else -1.0
        val next=ExperienceCase(key,user,modelId,ids.playbackSessionId!!,
            ContextFeatureExtractor.extract(snapshot,snapshot.capturedElapsedMs),reward,
            feedbackIds,nowEpochMs,"EXPLICIT_LEDGER_FEEDBACK",
            ids.contextSnapshotId,ids.recommendationId,ids.driveSessionId)
        append(next);true // Revise: most recent explicit command wins, not learned optimization.
    }
    /** Ordinary UI evidence has an actual ticket binding and the immutable ranking features, not a voice command. */
    fun retainUi(ledger:PipelineLedger,feedbackId:String,model:MusicEmbeddingRepository,now:Long):Boolean=synchronized(lock) {
        val rows=ledger.records();val f=rows.single{it.kind==LedgerKind.LISTENING_FEEDBACK && it.ids.feedbackEventId==feedbackId}
        require(f.ids.localUserId==user)
        val b=rows.single{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==f.ids.playbackSessionId}
        require(b.ids==f.ids.copy(feedbackEventId=null))
        val result=rows.single{it.kind==LedgerKind.RECOMMENDATION_RESULT && it.ids.recommendationId==f.ids.recommendationId}
        val root=rows.single{it.kind==LedgerKind.VOICE_REQUEST && it.ids.voiceRequestId==f.ids.voiceRequestId}
        require(root.payload.optString("source")=="ANDROID_UI")
        val evidence=result.payload.getJSONObject("frozenEvidence");val snapshot=evidence.getJSONObject("snapshot")
        require(snapshot.getString("localUserId")==user && snapshot.getString("appRunId")==f.ids.appRunId &&
            snapshot.getString("contextSnapshotId")==f.ids.contextSnapshotId && snapshot.optString("driveSessionId")==f.ids.driveSessionId)
        val mapping=evidence.getJSONObject("modelToMedia")
        val modelId=mapping.keys().asSequence().single{mapping.getString(it)==f.ids.musicId}
        require(model.contains(modelId))
        val kind=f.payload.getString("kind")
        val explicit=kind=="LIKE" && f.payload.optString("source")=="ANDROID_UI" && f.payload.optBoolean("explicitUiAction")
        val weak=kind in setOf("SUFFICIENT_LISTEN","EARLY_SKIP") && f.payload.optString("source")=="VERIFIED_LISTENING_SUMMARY"
        require(explicit||weak)
        val reward=if(explicit)1.0 else f.payload.getDouble("reward").also{require(it in setOf(.6,.2,-.5))}
        val oldCases=read();if(oldCases.any{feedbackId in it.feedbackEventIds})return@synchronized false
        val key=sha("${f.ids.appRunId}/${f.ids.playbackSessionId}");val old=oldCases.singleOrNull{it.caseId==key}
        if(!explicit && old?.origin=="UI_EXPLICIT_LEDGER_FEEDBACK")return@synchronized false
        val features=evidence.getJSONObject("decision").getJSONObject("observedFeatures")
        val facets=features.keys().asSequence().associateWith{k->features.getJSONObject(k).let{v->v.keys().asSequence().associateWith{v.getString(it)}}}
        append(ExperienceCase(key,user,modelId,f.ids.playbackSessionId!!,ObservedFeatures(facets),reward,
            old?.feedbackEventIds.orEmpty()+feedbackId,now,if(explicit)"UI_EXPLICIT_LEDGER_FEEDBACK" else "UI_VERIFIED_LISTENING_FEEDBACK",
            f.ids.contextSnapshotId,f.ids.recommendationId,f.ids.driveSessionId));true
    }
    private fun append(c: ExperienceCase) {
        require(c.localUserId==user)
        val j=JSONObject().put("schemaVersion",1).put("caseId",c.caseId).put("localUserId",user)
            .put("musicId",c.musicId).put("playbackSessionId",c.playbackSessionId).put("features",JSONObject(c.features.facets))
            .put("reward",c.reward).put("feedbackEventIds",org.json.JSONArray(c.feedbackEventIds.sorted()))
            .put("updatedAtEpochMs",c.updatedAtEpochMs).put("origin",c.origin)
            .put("contextSnapshotId",c.contextSnapshotId).put("recommendationId",c.recommendationId).put("driveSessionId",c.driveSessionId)
        val bytes=(j.toString()+"\n").toByteArray(Charsets.UTF_8)
        require(bytes.size<=128*1024 && file.length()+bytes.size<=16L*1024*1024)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        RandomAccessFile(file,"rw").use { out -> val before=out.length()
            try { out.seek(before);out.write(bytes);out.fd.sync() }
            catch(e:Exception) { runCatching { out.setLength(before) };throw e } }
    }
    companion object {
        private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
        fun forUser(root: File,user: String)=ExperienceCaseRepository(File(root,"ai-experiences/v1/${sha(user)}.jsonl"),user)
        private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun canonical(v:Any?):String=when(v) {
            null,JSONObject.NULL -> "null"
            is JSONObject -> v.keys().asSequence().sorted().joinToString(",","{","}") { k -> JSONObject.quote(k)+":"+canonical(v.get(k)) }
            is org.json.JSONArray -> (0 until v.length()).joinToString(",","[","]") { canonical(v.get(it)) }
            is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
            is Boolean -> v.toString()
            else -> JSONObject.quote(v.toString())
        }
    }
}
