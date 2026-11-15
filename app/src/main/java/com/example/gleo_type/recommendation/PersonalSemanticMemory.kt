package com.example.gleo_type.recommendation

/** Read-only semantic projection of persisted CBR, no duplicate preference store or invented labels.
 * Experimental analysis port; does NOT add a new weight to production ranking. */
data class PersonalSemanticProjection(val available:Boolean,val vector:FloatArray?,val cases:Int,val tracks:Int,val reason:String)
object PersonalSemanticMemory {
 fun project(user:String,intent:String,cases:List<ExperienceCase>,model:MusicEmbeddingRepository):PersonalSemanticProjection {
  val eligible=cases.filter{it.localUserId==user&&it.reward>0&&it.features.facets["voice"]?.get("command.musicIntent")==intent&&model.contains(it.musicId)}
   .distinctBy{it.playbackSessionId}.groupBy{it.musicId}.values.flatMap{it.sortedByDescending{c->c.updatedAtEpochMs}.take(3)}
  val tracks=eligible.map{it.musicId}.distinct()
  if(eligible.size<3||tracks.size<2)return PersonalSemanticProjection(false,null,eligible.size,tracks.size,"insufficient_verified_user_expression_evidence")
  val sum=FloatArray(512)
  eligible.forEach{c->val v=model.vector(c.musicId)!!;v.indices.forEach{i->sum[i]+=(v[i]*c.reward).toFloat()}}
  return PersonalSemanticProjection(true,PreferenceMath.normalize(sum),eligible.size,tracks.size,"experimental_positive_cbr_centroid_not_operating_rank_weight")
 }
}
