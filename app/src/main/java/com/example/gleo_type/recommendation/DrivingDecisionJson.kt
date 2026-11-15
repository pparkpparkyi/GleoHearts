package com.example.gleo_type.recommendation

import org.json.JSONArray
import org.json.JSONObject

/** Optional recommendation-history extension only. Original event JSONL is never changed. */
object DrivingDecisionJson {
    fun encode(d:DrivingDecision):JSONObject = JSONObject().put("version",1).put("applied",d.applied)
        .put("fallbackReason",d.fallbackReason ?: JSONObject.NULL).put("policyVersion",d.policyVersion).put("weight",d.weight)
        .put("scores",JSONArray(d.scores.map { JSONObject().put("musicId",it.musicId).put("preferenceScore",it.preferenceScore)
            .put("contextCorrection",it.contextCorrection).put("finalScore",it.finalScore)
            .put("matchingCatalogTags",JSONArray(it.matchingCatalogTags.sorted())) }))
        .put("input",d.input?.let { i -> JSONObject().also { j -> mapOf(
            "runId" to i.runId,"driveSessionId" to i.driveSessionId,"asOfElapsedMs" to i.asOfElapsedMs,
            "connectionToken" to i.connectionToken,"quality" to i.quality,"fallbackReason" to i.fallbackReason,
            "averageSpeedKmh" to i.averageSpeedKmh,"stopped" to i.stopped,"lowSpeed" to i.lowSpeed,
            "motionState" to i.motionState,"observedDurationMs" to i.observedDurationMs,"coverage" to i.coverage,
            "latestObservedElapsedMs" to i.latestObservedElapsedMs,"latestReceivedAt" to i.latestReceivedAt,
            "source" to i.source.name,"environment" to i.environment).forEach { (k,v) -> j.put(k,v ?: JSONObject.NULL) }
        } } ?: JSONObject.NULL)
    fun decode(j:JSONObject):DrivingDecision {
        require(j.getInt("version")==1)
        fun text(o:JSONObject,k:String)=if(o.isNull(k))null else o.getString(k)
        val i=j.optJSONObject("input")?.let { o -> DrivingRecommendationInput(o.getString("runId"),text(o,"driveSessionId"),
            o.getLong("asOfElapsedMs"),if(o.isNull("connectionToken"))null else o.getLong("connectionToken"),o.getString("quality"),
            text(o,"fallbackReason"),if(o.isNull("averageSpeedKmh"))null else o.getDouble("averageSpeedKmh"),
            if(o.isNull("stopped"))null else o.getBoolean("stopped"),if(o.isNull("lowSpeed"))null else o.getBoolean("lowSpeed"),
            text(o,"motionState"),o.getLong("observedDurationMs"),o.getDouble("coverage"),
            if(o.isNull("latestObservedElapsedMs"))null else o.getLong("latestObservedElapsedMs"),text(o,"latestReceivedAt"),
            ContextInputSource.valueOf(o.getString("source")),o.getString("environment")) }
        val scores=j.getJSONArray("scores")
        return DrivingDecision((0 until scores.length()).map { n -> scores.getJSONObject(n).let {
            DrivingScore(it.getString("musicId"),it.getDouble("preferenceScore"),it.getDouble("contextCorrection"),it.getDouble("finalScore"),
                it.optJSONArray("matchingCatalogTags")?.let { tags -> (0 until tags.length()).map { n -> tags.getString(n) }.toSet() } ?: emptySet()) } },
            i,j.getBoolean("applied"),text(j,"fallbackReason"),j.getString("policyVersion"),j.getDouble("weight"))
    }
}
