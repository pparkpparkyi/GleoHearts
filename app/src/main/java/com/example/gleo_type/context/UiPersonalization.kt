package com.example.gleo_type.context

import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import java.io.File

/** IO only. Historical VOICE_REQUEST column also carries explicitly labelled ANDROID_UI roots.
 * No spoken command, playback observation or user preference is fabricated by a click. */
class UiPersonalization(private val user:String,private val appRun:String,private val drive:()->String?,
    private val pipeline:ContextPipeline,private val elapsed:()->Long={android.os.SystemClock.elapsedRealtime()}) {
    private val ledger get()=pipeline.ledger
    @Synchronized fun prepare(run:RecommendationRun,music:String):PlaybackTicket? {
        if(drive()==null || run.origin!=RecommendationOrigin.AI_MODEL || run.driveId!=drive() || run.contextEvidence==null)return null
        val evidence=JSONObject(run.contextEvidence);val snapshot=evidence.getJSONObject("snapshot")
        require(snapshot.getString("localUserId")==user && snapshot.getString("appRunId")==appRun)
        require(snapshot.optString("driveSessionId")==drive() && music in run.musicIds)
        if(elapsed()-snapshot.getLong("capturedElapsedMs") !in 0..30000)return null // Play normally; stale context must not train.
        val root="ui-recommendation:${run.id}";val snap=snapshot.getString("contextSnapshotId")
        val ids=CausalIds(user,appRun,drive(),root,snap,run.id)
        fun append(kind:LedgerKind,key:String,payload:Map<String,Any?>,scope:CausalIds=ids) {
            val existing=ledger.records().singleOrNull{it.recordId==key}
            if(existing==null)ledger.append(kind,scope,payload,key)
            else require(existing.kind==kind && existing.ids==scope)
        }
        append(LedgerKind.VOICE_REQUEST,root,mapOf("source" to "ANDROID_UI","intent" to "RECOMMENDATION_SELECTION",
            "spokenCommand" to false,"environment" to snapshot.getString("environment")),ids.copy(contextSnapshotId=null,recommendationId=null))
        append(LedgerKind.CONTEXT_SNAPSHOT,snap,jsonMap(snapshot),ids.copy(recommendationId=null))
        append(LedgerKind.RECOMMENDATION_REQUEST,"ui-request:${run.id}",mapOf("source" to "ANDROID_UI"))
        append(LedgerKind.RECOMMENDATION_RESULT,"ui-result:${run.id}",mapOf("origin" to run.origin.name,
            "musicIds" to run.musicIds,"frozenEvidence" to jsonMap(evidence),"engineRunId" to run.id))
        return pipeline.preparePlayback(run.id,music)
    }
    /** Target IDs are captured at the click. Repeated likes on one occurrence reuse one event. */
    @Synchronized fun like(music:String,playback:String,observedDrive:String?,adapter:RecommendationModelAdapter):Boolean {
        if(observedDrive!=drive())return false
        val bound=ledger.records().singleOrNull{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.localUserId==user &&
            it.ids.appRunId==appRun && it.ids.driveSessionId==observedDrive && it.ids.playbackSessionId==playback && it.ids.musicId==music} ?: return false
        val event="ui-like:${bound.ids.appRunId}:$playback"
        if(ledger.records().none{it.recordId==event}) ledger.append(LedgerKind.LISTENING_FEEDBACK,bound.ids.copy(feedbackEventId=event),
            mapOf("source" to "ANDROID_UI","kind" to "LIKE","explicitUiAction" to true,"actualPlaybackVerified" to true),event)
        return adapter.learnUiFeedback(ledger,event)
    }
    companion object {
        private fun jsonValue(v:Any?):Any?=when(v){null,JSONObject.NULL->null;is JSONObject->jsonMap(v);is org.json.JSONArray->(0 until v.length()).map{jsonValue(v.get(it))};else->v}
        private fun jsonMap(j:JSONObject):Map<String,Any?> = j.keys().asSequence().associateWith{jsonValue(j.get(it))}
        /** Called only after the existing raw-journal validation and FixedFeedbackPolicy. No second listening calculation. */
        fun summary(root:File,user:String,input:PlaybackFeedback,reward:Double?,adapter:RecommendationModelAdapter) {
            if(reward==null)return
            val ledger=PipelineLedger(File(root,"ai-pipeline/v1/ledger.jsonl"))
            val bound=ledger.records().singleOrNull{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.localUserId==user &&
                "${it.ids.appRunId}/${it.ids.driveSessionId}/${it.ids.playbackSessionId}"==input.key} ?: return
            val result=ledger.records().single{it.kind==LedgerKind.RECOMMENDATION_RESULT && it.ids.recommendationId==bound.ids.recommendationId}
            if(!result.payload.has("frozenEvidence"))return
            val event="ui-summary:${input.key}"
            if(ledger.records().none{it.recordId==event}) ledger.append(LedgerKind.LISTENING_FEEDBACK,bound.ids.copy(feedbackEventId=event),mapOf(
                "source" to "VERIFIED_LISTENING_SUMMARY","kind" to if(reward<0)"EARLY_SKIP" else "SUFFICIENT_LISTEN",
                "sourceRecordKey" to input.key,"reward" to reward,"creditedWallMs" to input.creditedWallMs,
                "uniqueListeningRate" to input.uniqueListeningRate,"seekObserved" to input.seekObserved,
                "policy" to "existing_FixedFeedbackPolicy"),event)
            adapter.learnUiFeedback(ledger,event)
        }
    }
}
