package com.example.gleo_type.internalai

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.RecommendationRun
import com.example.gleo_type.ui.app.RecommendationOrigin
import org.json.JSONArray
import org.json.JSONObject

data class ExpressionEvidenceAudit(val proofs:List<ExpressionEvidenceProof>,val rejected:Int)

/** Reads existing validated ledger + recommendation history. No ID-nearest joins, writes or training.
 * Missing historical model/history evidence is a rejection, never replaced by the current snapshot. */
object LedgerExpressionEvidence {
    fun validate(cases:List<ExperienceCase>,ledger:PipelineLedger,runs:List<RecommendationRun>,model:MusicEmbeddingRepository):ExpressionEvidenceAudit {
        require(cases.size<=10000&&runs.size<=10000)
        val rows=ledger.records() // Existing contract validates causal parent order and unique event IDs.
        val proofs=cases.mapNotNull{c->runCatching {
            val bound=rows.single{it.kind==LedgerKind.PLAYBACK_BOUND&&it.ids.localUserId==c.localUserId&&
                it.ids.playbackSessionId==c.playbackSessionId&&it.ids.driveSessionId==c.driveSessionId&&
                it.ids.recommendationId==c.recommendationId&&it.ids.contextSnapshotId==c.contextSnapshotId}
            val ids=bound.ids
            val result=rows.single{it.kind==LedgerKind.RECOMMENDATION_RESULT&&it.ids.recommendationId==c.recommendationId&&it.ids.appRunId==ids.appRunId}
            val run=runs.single{it.id==result.payload.getString("engineRunId")}
            require(run.origin==RecommendationOrigin.AI_MODEL&&run.modelVersion==model.modelVersion&&run.catalogFingerprint==model.fingerprint&&run.driveId==c.driveSessionId)
            val evidence=JSONObject(requireNotNull(run.contextEvidence))
            result.payload.optJSONObject("frozenEvidence")?.let{require(canonical(it)==canonical(evidence))}
            if(!result.payload.has("frozenEvidence"))require(result.payload.getString("modelVersion")==model.modelVersion)
            val snapshot=evidence.getJSONObject("snapshot")
            require(snapshot.getString("localUserId")==c.localUserId&&snapshot.getString("appRunId")==ids.appRunId&&
                snapshot.getString("driveSessionId")==c.driveSessionId&&snapshot.getString("contextSnapshotId")==c.contextSnapshotId)
            val stored=rows.single{it.kind==LedgerKind.CONTEXT_SNAPSHOT&&it.ids.contextSnapshotId==c.contextSnapshotId&&it.ids.appRunId==ids.appRunId}
            require(canonical(stored.payload)==canonical(snapshot))
            require(evidence.getJSONObject("modelToMedia").getString(c.musicId)==ids.musicId&&model.contains(c.musicId))
            require(canonical(evidence.getJSONObject("decision").getJSONObject("observedFeatures"))==canonical(JSONObject(c.features.facets)))
            require(c.feedbackEventIds.isNotEmpty())
            val feedback=rows.filter{it.kind==LedgerKind.LISTENING_FEEDBACK&&it.ids.feedbackEventId in c.feedbackEventIds}
            require(feedback.size==c.feedbackEventIds.size&&feedback.all{it.ids.copy(feedbackEventId=null)==ids})
            val last=feedback.last();val payload=last.payload
            val reward=when {
                payload.optString("source")=="ANDROID_UI"&&payload.optString("kind")=="LIKE"&&payload.optBoolean("explicitUiAction")&&payload.optBoolean("actualPlaybackVerified")->1.0
                payload.optString("source")=="VERIFIED_LISTENING_SUMMARY"&&payload.optString("kind") in setOf("SUFFICIENT_LISTEN","EARLY_SKIP")&&payload.optString("policy")=="existing_FixedFeedbackPolicy"->
                    payload.getDouble("reward").also{require(it in setOf(.6,.2,-.5))}
                payload.optString("kind") in setOf("LIKE","DISLIKE")-> {
                    val trigger=payload.getString("triggerVoiceRequestId")
                    val execution=rows.single{it.kind==LedgerKind.COMMAND_EXECUTION&&it.payload.optString("triggerVoiceRequestId")==trigger}
                    require(execution.ids==ids&&execution.payload.getString("executionStatus")=="SUCCESS"&&execution.payload.getString("intent")==payload.getString("kind"))
                    if(payload.getString("kind")=="LIKE")1.0 else -1.0
                }
                else->error("Not trusted expression feedback")
            }
            require(c.reward==reward)
            val root=rows.single{it.kind==LedgerKind.VOICE_REQUEST&&it.ids.voiceRequestId==ids.voiceRequestId&&it.ids.appRunId==ids.appRunId}
            val env=snapshot.getString("environment").lowercase()
            val isolated=listOf("test","isolated","synthetic","replay","emulator").any{it in env}||root.payload.optString("source") in setOf("TEST_TEXT","SIMULATED_GLEO_ACTION")
            ExpressionEvidenceProof(c.caseId,model.fingerprint,ids.appRunId,
                if(isolated)ExpressionEvidenceEnvironment.ISOLATED_TEST else ExpressionEvidenceEnvironment.LIVE_LEDGER,true,true)
        }.getOrNull()}
        return ExpressionEvidenceAudit(proofs,cases.size-proofs.size)
    }
    private fun canonical(v:Any?):String=when(v){
        null,JSONObject.NULL->"null"
        is JSONObject->v.keys().asSequence().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
        is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
        is Number->java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is Boolean->v.toString()
        else->JSONObject.quote(v.toString())
    }
}
