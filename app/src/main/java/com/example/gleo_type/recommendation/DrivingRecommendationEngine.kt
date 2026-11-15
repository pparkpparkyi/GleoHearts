package com.example.gleo_type.recommendation

/** Untrained, disclosed tag bonus. It is not an inference of emotion, traffic or road safety. */
data class DrivingScorePolicy(val weight: Double = .05, val version: String = "experimental_catalog_tag_bonus_v1") {
    init { require(weight.isFinite() && weight in 0.0..0.2) }
    fun matchingTags(track: EmbeddingTrack, motion: String): Set<String> {
        val tags = track.genres + track.moods
        val targets = when(motion) {
            "stopped" -> setOf("acoustic","ambient","relaxing","calm","piano")
            "low_speed" -> setOf("calm","relaxing","ambient","chillout")
            "moving" -> setOf("instrumental","ambient","chillout")
            else -> emptySet()
        }
        return tags.intersect(targets)
    }
    fun bonus(track: EmbeddingTrack, motion: String): Double = if(matchingTags(track,motion).isEmpty()) 0.0 else 1.0
}
data class DrivingScore(val musicId:String, val preferenceScore:Double, val contextCorrection:Double, val finalScore:Double,
    val matchingCatalogTags:Set<String> = emptySet())
data class DrivingDecision(val scores:List<DrivingScore>, val input:DrivingRecommendationInput?, val applied:Boolean,
    val fallbackReason:String?, val policyVersion:String, val weight:Double)

class DrivingRecommendationEngine(private val model:MusicEmbeddingRepository,
    private val policy:DrivingScorePolicy = DrivingScorePolicy()) {
    fun rank(preference:FloatArray, topN:Int=10, excluded:Set<String> = emptySet(),
        previouslyListened:Set<String> = emptySet(), playableIds:Set<String>?=null,
        input:DrivingRecommendationInput?=null):DrivingDecision {
        require(topN in 1..500)
        val base=ClapRecommendationEngine(model).rank(preference,500,excluded,previouslyListened,playableIds)
        val valid=input?.let { it.quality=="available" && it.fallbackReason==null && it.driveSessionId!=null &&
            it.motionState in setOf("stopped","low_speed","moving") && it.averageSpeedKmh?.let { v -> v.isFinite() && v>=0 }==true &&
            it.coverage.isFinite() && it.coverage in .6..1.0 && it.observedDurationMs>=6000 &&
            it.latestObservedElapsedMs?.let { ms -> input.asOfElapsedMs-ms in 0..2500 }==true }==true
        val applied=valid && policy.weight>0
        val metadata=model.tracks.associateBy { it.musicId }
        val scored=base.map { row ->
            val correction=if(applied) policy.weight*policy.bonus(metadata.getValue(row.musicId),input!!.motionState!!) else 0.0
            DrivingScore(row.musicId,row.score,correction,if(applied) row.score+correction else row.score,
                if(applied) policy.matchingTags(metadata.getValue(row.musicId),input!!.motionState!!) else emptySet())
        }.sortedWith(compareByDescending<DrivingScore> { it.finalScore }.thenBy { it.musicId }).take(topN)
        return DrivingDecision(scored,input,applied,if(applied)null else input?.fallbackReason ?:
            if(policy.weight==0.0)"policy_disabled" else "context_unavailable",policy.version,policy.weight)
    }
}
