package com.example.gleo_type.ui.app

import java.security.MessageDigest

data class CarMusicCandidate(val trackId:String,val mediaId:String?,val title:String,val artist:String,
    val genres:Set<String>,val moods:Set<String>,val recommendationScore:Double?,val tasteScore:Double?,
    val contextAdjustment:Double?,val requestAdjustment:Double?,val finalScore:Double?,val playable:Boolean,
    val mappingStatus:String,val contextAvailability:String,val fallbackReason:String?,val source:String,
    val confidence:String="catalog_metadata_not_acoustic_inference",val bpmEstimated:Double?=null,
    val energy:Double?=null,val albumArtUri:String?=null)
data class CarMusicSnapshot(val run:RecommendationRun,val candidates:List<CarMusicCandidate>,
    val playableRecommendations:List<CarMusicCandidate>,val excludedCandidates:Map<String,String>,val modelRows:Int,
    val contextSnapshot:com.example.gleo_type.context.ContextSnapshot?=null)
object CarMusicPlaybackGate {
    fun resolve(target:CarMusicCandidate,catalog:List<Track>):Track? =
        if(!target.playable || target.mappingStatus!="VERIFIED_LOCAL_SHA256" || target.mediaId==null)null
        else catalog.singleOrNull{it.id==target.mediaId}
}
data class CarAcousticFeatures(val trackId:String,val bpmEstimated:Double?=null,val energy:Double?=null,
    val moods:Set<String> = emptySet(),val source:String="unavailable")
data class CarArtworkReference(val trackId:String,val mediaId:String,val imageId:String,val assetPath:String,
    val sha256:String,val source:String,val permission:String,val licenseReference:String)
object CarArtworkPolicy {
    fun accepts(ref:CarArtworkReference,trackId:String,mediaId:String,bytes:ByteArray):Boolean =
        ref.trackId==trackId && ref.mediaId==mediaId && ref.imageId.isNotBlank() && ref.source.isNotBlank() &&
        ref.permission=="APPROVED_INTERNAL_USE" && ref.licenseReference.isNotBlank() &&
        ref.assetPath.startsWith("car_v10/artwork/") && !ref.assetPath.contains("..") &&
        bytes.size in 1..2_097_152 && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){"%02x".format(it.toInt() and 255)}==ref.sha256
}
data class CarBackground(val origin:String,val colors:List<Long>,val featuresUsed:Set<String>)
object CarBackgroundPolicy {
    private fun mix(a:Long,b:Long,t:Double):Long {
        fun channel(s:Int)=(((a shr s) and 255)*(1-t)+((b shr s) and 255)*t).toLong()
        return 0xff000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    fun resolve(features:CarAcousticFeatures?,dark:Boolean,approvedArt:Boolean=false):CarBackground {
        val base=CarV10Design.colors(dark).backgroundPrimary
        if(approvedArt)return CarBackground("APPROVED_ARTWORK",listOf(base,base),setOf("approved_image"))
        val bpm=features?.bpmEstimated?.takeIf{it.isFinite() && it>0}?.coerceIn(40.0,240.0)?.let{(it-40)/200}
        val energy=features?.energy?.takeIf{it.isFinite()}?.coerceIn(0.0,1.0)
        val moods=features?.moods.orEmpty().filter{it.isNotBlank()}.sorted()
        if(bpm==null && energy==null && moods.isEmpty())return CarBackground("PLEOS_TOKENS",listOf(base,base),emptySet())
        val accent=if(moods.joinToString("|").hashCode().ushr(1)%2==0)CarV10Design.musicPrimary else 0xff9670c5L
        val amount=if(dark).10 else .055
        val used=buildSet{if(bpm!=null)add("bpmEstimated");if(energy!=null)add("energy");if(moods.isNotEmpty())add("catalog_moods")}
        return CarBackground("CATALOG_FEATURE_GRADIENT",listOf(mix(base,accent,amount*(.5+(bpm ?: energy ?: .5)*.5)),
            mix(base,CarV10Design.musicPrimary,amount*(.5+(energy ?: bpm ?: .5)*.5))),used)
    }
}
