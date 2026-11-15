package com.example.gleo_type.internalai

import com.example.gleo_type.recommendation.*

enum class ExpressionEvidenceEnvironment { LIVE_LEDGER, ISOLATED_TEST }
/** Issued by a causal-ledger validator, not from UI labels/Hearts actions. No free-text storage. */
data class ExpressionEvidenceProof(val caseId:String,val modelFingerprint:String,val appRunId:String,
    val environment:ExpressionEvidenceEnvironment,val playbackObserved:Boolean,val feedbackTrusted:Boolean)
data class LearnedExpression(val owner:String,val expression:String,val fingerprint:String,val vector:FloatArray?,
    val playbackCount:Int,val distinctTracks:Int,val reason:String)
data class ExpressionScore(val musicId:String,val preferenceScore:Double,val semanticScore:Double?,val total:Double)

/** Projection of the existing CBR journal; owns no parallel preference store.
 * Explicitly opt-in scoring. The default semantic weight is zero and production callers are untouched. */
class UserExpressionEngine(private val model:MusicEmbeddingRepository,private val maxAgeMs:Long=90L*24*60*60*1000,
    private val maxPerTrack:Int=3) {
    init{require(maxAgeMs>0&&maxPerTrack in 1..10)}
    fun learn(user:String,expression:String,cases:List<ExperienceCase>,proofs:List<ExpressionEvidenceProof>,
        nowEpochMs:Long,environment:ExpressionEvidenceEnvironment=ExpressionEvidenceEnvironment.LIVE_LEDGER):LearnedExpression {
        require(user.isNotBlank()&&expression in setOf("ENERGETIC","CALM")&&nowEpochMs>=0)
        require(cases.size<=10000&&proofs.size<=10000)
        require(proofs.map{it.caseId}.distinct().size==proofs.size)
        val proof=proofs.associateBy{it.caseId}
        val validOrigins=setOf("EXPLICIT_LEDGER_FEEDBACK","UI_EXPLICIT_LEDGER_FEEDBACK","UI_VERIFIED_LISTENING_FEEDBACK")
        val eligible=cases.filter{c->
            val p=proof[c.caseId]
            c.localUserId==user && c.updatedAtEpochMs in 0..nowEpochMs && nowEpochMs-c.updatedAtEpochMs<=maxAgeMs &&
                c.reward.isFinite()&&c.reward!=0.0&&c.reward in -1.0..1.0&&c.origin in validOrigins&&
                c.playbackSessionId.isNotBlank()&&c.feedbackEventIds.isNotEmpty()&&
                !c.contextSnapshotId.isNullOrBlank()&&!c.recommendationId.isNullOrBlank()&&!c.driveSessionId.isNullOrBlank()&&
                expressionOf(c.features)==expression&&model.contains(c.musicId)&&
                p!=null&&p.modelFingerprint==model.fingerprint&&p.appRunId.isNotBlank()&&p.environment==environment&&
                p.playbackObserved&&p.feedbackTrusted
        }.sortedWith(compareByDescending<ExperienceCase>{it.updatedAtEpochMs}.thenBy{it.caseId})
        // A reused playback UUID on different runs is distinct. Conflicting same-run revisions are rejected.
        val perOccurrence=eligible.groupBy{proof.getValue(it.caseId).appRunId to it.playbackSessionId}.values.mapNotNull{revisions->
            if(revisions.map{it.musicId}.distinct().size!=1)null else revisions.first()
        }
        val seenFeedback=mutableSetOf<String>()
        val dedup=perOccurrence.filter{c->
            val keys=c.feedbackEventIds.map{proof.getValue(c.caseId).appRunId+"/"+it}
            if(keys.any{it in seenFeedback})false else {seenFeedback.addAll(keys);true}
        }.groupBy{it.musicId}.values.flatMap{it.take(maxPerTrack)}.sortedBy{it.caseId}
        val positive=dedup.filter{it.reward>0}
        val tracks=dedup.map{it.musicId}.distinct().size
        fun result(v:FloatArray?,reason:String)=LearnedExpression(user,expression,model.fingerprint,v,dedup.size,tracks,reason)
        if(dedup.size<3||positive.map{it.musicId}.distinct().size<2)return result(null,"insufficient_distinct_positive_playback_evidence")
        val sum=FloatArray(512)
        dedup.forEach{c->val v=model.vector(c.musicId)!!;v.indices.forEach{i->sum[i]+=(v[i]*c.reward).toFloat()}}
        val vector=runCatching{PreferenceMath.normalize(sum)}.getOrNull() ?: return result(null,"cancelled_or_invalid_centroid")
        return result(vector,"bounded_signed_cbr_centroid_experimental")
    }
    private fun expressionOf(features:ObservedFeatures):String? {
        val command=features.facets["voice"].orEmpty()
        command["command.musicIntent"]?.takeIf{it in setOf("ENERGETIC","CALM")}?.let{return it}
        // Conservative bridge for older records whose parser left UNKNOWN but retained the exact request.
        val normalized=command["command.text"]?.filterNot{it.isWhitespace()||it in ".!?"}
        return when(normalized){
            "신나는노래틀어줘","신나는음악틀어줘","신나는음악추천해줘"->"ENERGETIC"
            "잔잔한음악추천해줘","잔잔한음악틀어줘"->"CALM"
            else->null
        }
    }
    fun rank(user:String,expression:String,preference:FloatArray,learned:LearnedExpression?,semanticWeight:Double=0.0,
        excluded:Set<String> = emptySet(),playable:Set<String>?=null,topN:Int=10):List<ExpressionScore> {
        require(semanticWeight.isFinite()&&semanticWeight in 0.0..0.2&&topN in 1..500)
        val semantic=learned?.takeIf{it.owner==user&&it.expression==expression&&it.fingerprint==model.fingerprint}
            ?.vector?.let{PreferenceMath.normalize(it)}
        return ClapRecommendationEngine(model).rank(preference,500,excluded,playableIds=playable).map{base->
            val score=semantic?.let{v->val audio=model.vector(base.musicId)!!;v.indices.sumOf{i->v[i].toDouble()*audio[i]}}
            ExpressionScore(base.musicId,base.score,score,base.score+semanticWeight*(score ?: 0.0))
        }.sortedWith(compareByDescending<ExpressionScore>{it.total}.thenBy{it.musicId}).take(topN)
    }
}
