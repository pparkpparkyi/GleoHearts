package com.example.gleo_type.ui.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

interface RecommendationHistory {
    fun recordPlaybackEvidence(runId:String,trackId:String,mediaId:String,stage:String,playbackId:String?,at:String,appRunId:String?,driveId:String?) {}
    fun load(): List<RecommendationRun>
    fun append(run: RecommendationRun)
    fun submit(run: RecommendationRun, completed: (Throwable?) -> Unit) {
        runCatching { append(run) }.fold({ completed(null) }, completed)
    }
}
class MemoryRecommendationHistory : RecommendationHistory {
    private val runs = mutableListOf<RecommendationRun>()
    @Synchronized override fun load() = runs.toList()
    @Synchronized override fun append(run: RecommendationRun) { if (runs.none { it.id == run.id }) runs += run }
}
class LocalRecommendationHistory(private val directory: File) : RecommendationHistory {
    override fun recordPlaybackEvidence(runId:String,trackId:String,mediaId:String,stage:String,playbackId:String?,at:String,appRunId:String?,driveId:String?) {
        require(stage in setOf("selected","observed_started","already_playing","request_failed","unconfirmed"))
        UiWriteDispatch.submit({
            synchronized(this) {
                check(directory.isDirectory || directory.mkdirs())
                val target=File(directory,"playback-evidence.jsonl")
                val bytes=(JSONObject().put("version",1).put("recommendationRunId",runId).put("trackId",trackId).put("mediaId",mediaId)
                    .put("stage",stage).put("playbackSessionId",playbackId ?: JSONObject.NULL).put("observedAt",at)
                    .put("appRunId",appRunId ?: JSONObject.NULL).put("driveSessionId",driveId ?: JSONObject.NULL).toString()+"\n").toByteArray(Charsets.UTF_8)
                check(target.length()+bytes.size<=8L*1024*1024)
                RandomAccessFile(target,"rw").use{f->val length=f.length();try{f.seek(length);f.write(bytes);f.fd.sync()}catch(e:Exception){runCatching{f.setLength(length)};throw e}}
            }
        },{android.util.Log.e("CarRecommendation","Evidence write failed",it)})
    }
    private val file get() = File(directory, "runs.jsonl")
    @Synchronized override fun load(): List<RecommendationRun> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        check(bytes.isEmpty() || bytes.last() == 10.toByte()) { "Incomplete recommendation run; original preserved" }
        return bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map { decode(JSONObject(it)) }.toList()
    }
    override fun submit(run: RecommendationRun, completed: (Throwable?) -> Unit) {
        UiWriteDispatch.submit({ append(run); completed(null) }, { completed(it) })
    }
    @Synchronized override fun append(run: RecommendationRun) {
        require(run.origin == RecommendationOrigin.TEST_CATALOG && run.modelVersion == null ||
            run.origin == RecommendationOrigin.AI_MODEL && run.modelVersion?.startsWith("clap500-fixed015-")==true &&
            run.scores.keys==run.musicIds.toSet() && run.scores.values.all { it.isFinite() })
        check(directory.isDirectory || directory.mkdirs())
        val prior = load()
        if (prior.any { it.id == run.id }) return
        val bytes = (encode(run).toString() + "\n").toByteArray(Charsets.UTF_8)
        check(file.length() + bytes.size <= 8L * 1024 * 1024) { "Recommendation history full; export first" }
        RandomAccessFile(file, "rw").use { out ->
            val original = out.length()
            try { out.seek(original); out.write(bytes); out.fd.sync() }
            catch (e: Exception) { runCatching { out.setLength(original); out.fd.sync() }; throw e }
        }
    }
    private fun encode(r: RecommendationRun) = JSONObject().put("version",1).put("id",r.id).put("createdAt",r.createdAt)
        .put("catalogObservedAt",r.catalogObservedAt ?: JSONObject.NULL).put("origin",r.origin.name)
        .put("algorithm",r.algorithm).put("modelVersion",r.modelVersion ?: JSONObject.NULL).put("scope",r.scope.name)
        .put("variant",r.variant).put("musicIds",JSONArray(r.musicIds)).put("catalogFingerprint",r.catalogFingerprint)
        .put("driveSessionId",r.driveId ?: JSONObject.NULL).put("usedFeatures",JSONArray(r.usedFeatures.map {
            JSONObject().put("name",it.name).put("source",it.source).put("observedAt",it.observedAt ?: JSONObject.NULL)
        })).put("scores",JSONObject(r.scores)).put("contextEvidence",r.contextEvidence ?: JSONObject.NULL).put("vehicleFeaturesUsed", r.drivingDecision?.applied==true).put("drivingDecision",
            r.drivingDecision?.let(com.example.gleo_type.recommendation.DrivingDecisionJson::encode) ?: JSONObject.NULL).put("aiInvoked",r.origin==RecommendationOrigin.AI_MODEL)
    private fun decode(j: JSONObject): RecommendationRun {
        check(j.getInt("version") == 1)
        val ids = j.getJSONArray("musicIds"); val features = j.getJSONArray("usedFeatures")
        return RecommendationRun(j.getString("id"),j.getString("createdAt"),j.text("catalogObservedAt"),
            RecommendationOrigin.valueOf(j.getString("origin")),j.getString("algorithm"),j.text("modelVersion"),
            MusicScope.valueOf(j.getString("scope")),j.getInt("variant"),(0 until ids.length()).map { ids.getString(it) },
            j.getString("catalogFingerprint"),(0 until features.length()).map { i -> features.getJSONObject(i).let {
                UsedFeature(it.getString("name"),it.getString("source"),it.text("observedAt")) } },j.text("driveSessionId"),
            j.optJSONObject("scores")?.let { scores -> scores.keys().asSequence().associateWith { scores.getDouble(it) } } ?: emptyMap(),
            j.optJSONObject("drivingDecision")?.let(com.example.gleo_type.recommendation.DrivingDecisionJson::decode),contextEvidence=j.text("contextEvidence"))
    }
}
