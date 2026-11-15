package com.example.gleo_type.ui.app

import java.time.Instant
import java.util.UUID

data class RuntimeHeartCapture(val pinEventId:String,val playbackSessionId:String,val occurredAt:String,
    val receivedElapsedMs:Long,val positionMs:Long,val durationMs:Long?,val contextSnapshotId:String?,
    val contextSnapshotJson:String?,val recommendationId:String?,val recommendationSnapshotJson:String?,
    val environment:String,val locationSource:String?)
data class RuntimeHeartMetadata(val localUserId:String,val appRunId:String,val sourceDeviceId:String,
    val captures:List<RuntimeHeartCapture>,val syncState:String="LOCAL_ONLY_TRANSPORT_NOT_CONFIRMED")
data class RuntimeHeartRequest(val localUserId:String,val appRunId:String,val driveSessionId:String,val sourceDeviceId:String,
    val track:Track,val capture:RuntimeHeartCapture,val place:Place?)

/** Same drive/track identity as Review. No model, feedback or network calls. */
object RuntimeHeartProjection {
 fun merge(existing:Pin?,input:RuntimeHeartRequest):Pin {
  with(input){require(listOf(localUserId,appRunId,driveSessionId,sourceDeviceId,track.id,capture.playbackSessionId,capture.pinEventId).all{it.isNotBlank()&&it.length<=1024})
   Instant.parse(capture.occurredAt);require(capture.positionMs>=0&&capture.receivedElapsedMs>=0&&(capture.durationMs==null||capture.durationMs>0))
   require(listOfNotNull(capture.contextSnapshotJson,capture.recommendationSnapshotJson).all{it.toByteArray(Charsets.UTF_8).size<=65536})
   require(place==null||(place.latitude.isFinite()&&place.longitude.isFinite()&&place.latitude in -90.0..90.0&&place.longitude in -180.0..180.0))
   val key="$appRunId/$driveSessionId"
   require(existing==null || (existing.sourceDriveKey==key&&existing.track?.id==track.id))
   require(existing?.runtimeHeart?.localUserId?.let{it==localUserId}!=false && existing?.heartsReview?.localUserId?.let{it==localUserId}!=false)
   val prior=existing?.runtimeHeart
   require(prior==null||(prior.appRunId==appRunId&&prior.sourceDeviceId==sourceDeviceId))
   val old=prior?.captures.orEmpty()
   val duplicate=old.firstOrNull{it.pinEventId==capture.pinEventId||it.playbackSessionId==capture.playbackSessionId}
   require(duplicate==null || duplicate.playbackSessionId==capture.playbackSessionId)
   val captures=if(duplicate!=null)old else old+capture;require(captures.size<=32){"Runtime moment limit reached"}
   val metadata=RuntimeHeartMetadata(localUserId,appRunId,sourceDeviceId,captures)
   val base=existing ?: Pin(UUID.nameUUIDFromBytes((key+"/"+track.id).toByteArray(Charsets.UTF_8)).toString(),capture.occurredAt,
    driveSessionId,track,place,emptyList(),"",sourceDriveKey=key,occurredAt=capture.occurredAt)
   return base.copy(runtimeHeart=metadata,deletedAt=null)
  }
 }
 /** One touch, not editing; unknown/stale/full restrictions fail closed. OEM approval remains unverified. */
 fun carTouchAllowed(ux:DrivingUx,now:Long)=ux.known&&ux.source=="CarUxRestrictionsManager"&&
  ux.activeRestrictions in 0..510&&ux.observedElapsedMs?.let{now-it in 0..6000}==true
}
