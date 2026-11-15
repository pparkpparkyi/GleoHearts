package com.example.gleo_type.recommendation

import com.example.gleo_type.context.*

enum class ProfileFeedbackOutcome { APPLIED_FIXED_RULE, ALREADY_APPLIED, RECORDED_SAME_POLARITY,
    NEUTRAL_NOT_APPLIED, NO_PROFILE, LEGACY_SUMMARY_ALREADY_PROCESSED, OLDER_EVENT_NOT_APPLIED }

/** Explicit feedback needs no inferred listening label. The values reuse the existing fixed update range. */
object ExplicitProfilePolicy {
    const val version="explicit_ledger_fixed_rule_v1"
    fun reward(kind:String)=when(kind){"LIKE"->1.0;"DISLIKE"->-.5;else->null}
}

data class ValidatedProfileFeedback(val eventId:String,val occurrenceKey:String,val musicId:String,
    val kind:String,val ledgerIndex:Int)
object LedgerProfileFeedback {
    fun validate(ledger:PipelineLedger,user:String,eventId:String):ValidatedProfileFeedback? {
        val rows=ledger.records()
        val f=rows.single{it.kind==LedgerKind.LISTENING_FEEDBACK && it.ids.feedbackEventId==eventId}
        require(f.ids.localUserId==user)
        if(f.payload.getString("kind") !in setOf("LIKE","DISLIKE"))return null
        val bound=rows.single{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==f.ids.playbackSessionId}
        require(bound.ids==f.ids.copy(feedbackEventId=null))
        val trigger=f.payload.getString("triggerVoiceRequestId")
        val command=rows.single{it.kind==LedgerKind.COMMAND_EXECUTION && it.payload.optString("triggerVoiceRequestId")==trigger}
        require(command.ids==bound.ids && command.payload.getString("executionStatus")=="SUCCESS" &&
            command.payload.getString("intent")==f.payload.getString("kind"))
        require(f.ids.driveSessionId!=null && f.ids.playbackSessionId!=null && f.ids.musicId!=null)
        val result=rows.single { it.kind==LedgerKind.RECOMMENDATION_RESULT && it.ids.recommendationId==f.ids.recommendationId }
        val frozen=result.payload.optJSONObject("frozenEvidence")
        val modelId=if(frozen==null)f.ids.musicId else {
            val s=frozen.getJSONObject("snapshot")
            require(s.getString("localUserId")==user && s.getString("appRunId")==f.ids.appRunId &&
                s.getString("contextSnapshotId")==f.ids.contextSnapshotId && s.optString("driveSessionId")==f.ids.driveSessionId)
            val mapping=frozen.getJSONObject("modelToMedia")
            mapping.keys().asSequence().single { mapping.getString(it)==f.ids.musicId }
        }
        return ValidatedProfileFeedback(eventId,"${f.ids.appRunId}/${f.ids.driveSessionId}/${f.ids.playbackSessionId}",
            modelId,f.payload.getString("kind"),rows.indexOf(f))
    }
}
