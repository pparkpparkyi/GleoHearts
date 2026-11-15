package com.example.gleo_type.internalai

import com.example.gleo_type.context.ContextSnapshot
import com.example.gleo_type.recommendation.*

data class CounterfactualContribution(val facet:String,val scoreDelta:Double,val rankWithout:Int)
data class AuditedCandidate(val musicId:String,val score:Double,val rank:Int,
    val arithmeticParts:Map<String,Double>,val removals:List<CounterfactualContribution>)

/** Read-only diagnostic of the actual ranker, NOT an additional ranking layer.
 * Facet ablations are interventions on inputs, not learned causal attribution; they need not add up. */
object ContextContributionAudit {
    fun inspect(ranker:ContextFusionRanker,user:String,run:String,drive:String?,preference:FloatArray,
        snapshot:ContextSnapshot,nowElapsed:Long,nowEpoch:Long,text:String?,cases:List<ExperienceCase>,
        playable:Set<String>?=null,topN:Int=10):List<AuditedCandidate> {
        require(snapshot.localUserId==user&&snapshot.appRunId==run&&snapshot.driveSessionId==drive)
        require(nowElapsed>=snapshot.capturedElapsedMs&&topN in 1..500)
        fun evaluate(s:ContextSnapshot,t:String?)=ranker.rank(user,preference,s,nowElapsed,nowEpoch,t,cases,playable,topK=500).scores
        val full=evaluate(snapshot,text)
        val variants=linkedMapOf(
            "location" to (snapshot.copy(currentLocation=emptyMap()) to text),
            "destination" to (snapshot.copy(destination=emptyMap()) to text),
            "driving" to (snapshot.copy(driving=emptyMap(),drivingInput=snapshot.drivingInput.copy(
                quality="unknown",fallbackReason="diagnostic_driving_removed")) to text),
            "road" to (snapshot.copy(road=emptyMap()) to text),
            "timeWeather" to (snapshot.copy(timeWeather=emptyMap()) to text),
            "voice" to (snapshot.copy(voice=emptyMap()) to null))
        val ablations=variants.mapValues{(_,v)->evaluate(v.first,v.second)}
        return full.take(topN).mapIndexed{i,row->
            check(kotlin.math.abs(row.total-row.contributions.values.sum())<1e-9)
            AuditedCandidate(row.musicId,row.total,i+1,row.contributions.toMap(),ablations.map{(facet,rows)->
                val rank=rows.indexOfFirst{it.musicId==row.musicId};check(rank>=0)
                CounterfactualContribution(facet,row.total-rows[rank].total,rank+1)
            })
        }
    }
}
