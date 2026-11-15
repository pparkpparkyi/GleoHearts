package com.example.gleo_type.recommendation

enum class MusicRequestSource { TEST_TEXT, GLEO_ACTION }
enum class MusicRequestIntent { CALM, ENERGETIC, DRIVING, ALTERNATIVE, UNKNOWN }
data class MusicRequest(val intent:MusicRequestIntent, val source:MusicRequestSource,
    val status:String, val parserVersion:String="ko_exact_rules_v1")

/** Conservative local rules, not CLAP text inference, ASR, or durable preference learning. */
object MusicRequestParser {
    fun parse(text:String):MusicRequest {
        val normalized=text.trim().replace(Regex("\\s+")," ").removeSuffix(".")
        val intent=when(normalized) {
            "잔잔한 음악 추천해 줘", "잔잔한 음악 틀어 줘" -> MusicRequestIntent.CALM
            "신나는 음악 추천해 줘", "신나는 음악 틀어 줘" -> MusicRequestIntent.ENERGETIC
            "지금 운전에 어울리는 음악 추천해 줘" -> MusicRequestIntent.DRIVING
            "이 노래 말고 다른 곡 추천해 줘", "다른 노래 추천해 줘" -> MusicRequestIntent.ALTERNATIVE
            else -> MusicRequestIntent.UNKNOWN
        }
        return MusicRequest(intent,MusicRequestSource.TEST_TEXT,
            if(intent==MusicRequestIntent.UNKNOWN)"unsupported_or_ambiguous" else "recognized")
    }
}

/** No exported receiver or speculative SDK calls. Replace after registered schema/device validation. */
interface GleoMusicRequestAdapter { val supportStatus:String; fun receive():MusicRequest? }
class UnavailableGleoMusicRequestAdapter:GleoMusicRequestAdapter {
    override val supportStatus="not_registered_or_device_verified"
    override fun receive():MusicRequest?=null
}

data class RequestScore(val musicId:String,val preferenceScore:Double,val contextCorrection:Double,
    val requestCorrection:Double,val finalScore:Double,val matchedTags:Set<String>)
data class MusicRequestDecision(val scores:List<RequestScore>,val request:MusicRequest?,val status:String)
class NaturalLanguageRecommendationEngine(private val model:MusicEmbeddingRepository,
    private val drivingPolicy:DrivingScorePolicy=DrivingScorePolicy(),private val requestWeight:Double=.08) {
    init { require(requestWeight.isFinite() && requestWeight in 0.0..0.2) }
    fun rank(preference:FloatArray,topN:Int=10,excluded:Set<String> = emptySet(),
        previouslyListened:Set<String> = emptySet(),playableIds:Set<String>?=null,
        input:DrivingRecommendationInput?=null,request:MusicRequest?=null,currentModelId:String?=null):Pair<DrivingDecision,MusicRequestDecision> {
        require(topN in 1..500)
        val accepted=request?.takeIf { it.status=="recognized" && it.intent!=MusicRequestIntent.UNKNOWN }
        val blocked=if(accepted?.intent==MusicRequestIntent.ALTERNATIVE && currentModelId!=null)excluded+currentModelId else excluded
        val base=DrivingRecommendationEngine(model,drivingPolicy).rank(preference,500,blocked,previouslyListened,playableIds,input)
        val desired=when(accepted?.intent) {
            MusicRequestIntent.CALM -> setOf("calm","relaxing","ambient","chillout")
            MusicRequestIntent.ENERGETIC -> setOf("energetic","upbeat","dance","happy")
            else -> emptySet()
        }
        val metadata=model.tracks.associateBy { it.musicId }
        val result=base.scores.map { row ->
            val t=metadata.getValue(row.musicId);val tags=(t.genres+t.moods).intersect(desired)
            val correction=if(tags.isEmpty())0.0 else requestWeight
            RequestScore(row.musicId,row.preferenceScore,row.contextCorrection,correction,
                if(correction==0.0)row.finalScore else row.finalScore+correction,tags)
        }.sortedWith(compareByDescending<RequestScore>{it.finalScore}.thenBy{it.musicId}).take(topN)
        val status=when {
            request==null -> "no_request"
            accepted==null -> "unsupported_request_preference_fallback"
            accepted.intent==MusicRequestIntent.ALTERNATIVE && currentModelId==null -> "current_track_unknown"
            accepted.intent==MusicRequestIntent.DRIVING -> if(base.applied)"existing_vehicle_context" else "vehicle_unknown_preference_fallback"
            desired.isNotEmpty() && result.none { it.matchedTags.isNotEmpty() } -> "no_matching_playable_tags"
            else -> "explicit_request_applied"
        }
        return base to MusicRequestDecision(result,request,status)
    }
}
