package com.example.gleo_type.sync

import com.example.gleo_type.ui.app.*

/** Explicit caller authorization is needed: historical DriveRecord does not carry an owner ID.
 * Preparation only; not called by startup, never uploads or writes archive/events. */
object ArchiveSyncProjection {
    fun unreviewed(record:DriveRecord,catalog:List<Track>,authorizedOwnerId:String,sourceDeviceId:String,
        ownershipVerified:Boolean,revision:Long,recommendationByPlayback:Map<String,String> = emptyMap()):List<ArchiveSyncRecord> {
        require(ownershipVerified && authorizedOwnerId.isNotBlank())
        require(record.startedAt!=null && record.endedAt!=null && record.durationMs!=null)
        return record.tracks.distinct().flatMap { music ->
            val track=catalog.singleOrNull{it.id==music}
            val playbacks=record.playbacks.filter{it.musicId==music}.map{it.playbackSessionId}.distinct()
            (if(playbacks.isEmpty())listOf(null) else playbacks).map { playback ->
                ArchiveSyncRecord(authorizedOwnerId,sourceDeviceId,
                    "${record.key}/"+(playback?.let{"playback/$it"} ?: "music/$music"),revision,
                    record.id,music,playback,playback?.let{recommendationByPlayback[it]},null,
                    record.endedAt,ArchiveRecordKind.UNREVIEWED_MUSIC,track?.title ?: music,track?.artist ?: "메타데이터 미제공")
            }
        }
    }
    fun heart(pin:Pin,sourceDeviceId:String,revision:Long,updatedAt:String):ArchiveSyncRecord {
        val review=requireNotNull(pin.heartsReview){"Archive owner not verified"}
        val track=requireNotNull(pin.track);val drive=requireNotNull(pin.driveId)
        // A review's list of playback IDs has no per-track relation; never guess one.
        return ArchiveSyncRecord(review.localUserId,sourceDeviceId,"archive/${pin.id}",revision,drive,track.id,
            null,null,pin.id,updatedAt,ArchiveRecordKind.HEARTS_ARCHIVE,track.title,track.artist,deleted=pin.deletedAt!=null)
    }
}
