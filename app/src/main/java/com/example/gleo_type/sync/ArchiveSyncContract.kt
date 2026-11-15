package com.example.gleo_type.sync

import java.time.Instant
import java.security.MessageDigest

enum class ArchiveSyncState { LOCAL_ONLY, PENDING_TRANSPORT, RECEIVED_IN_ISOLATED_TEST }
enum class ArchiveRecordKind { UNREVIEWED_MUSIC, HEARTS_ARCHIVE }

/** Transport-neutral v1. No network client, remote account, or implicit identity linking. */
data class ArchiveSyncRecord(
    val localUserId:String, val sourceDeviceId:String, val sourceRecordId:String, val revision:Long,
    val driveSessionId:String, val musicId:String, val playbackSessionId:String?,
    val recommendationId:String?, val archiveId:String?, val updatedAt:String,
    val kind:ArchiveRecordKind, val title:String, val artist:String,
    val syncState:ArchiveSyncState=ArchiveSyncState.LOCAL_ONLY, val deleted:Boolean=false, val schemaVersion:Int=1
) {
    init {
        require(schemaVersion==1 && revision>0)
        require(listOf(localUserId,sourceDeviceId,sourceRecordId,driveSessionId,musicId).all{it.isNotBlank() && it.length<=1024})
        require(listOfNotNull(playbackSessionId,recommendationId,archiveId).all{it.isNotBlank() && it.length<=1024})
        require(title.length<=4096 && artist.length<=4096);Instant.parse(updatedAt)
        require(kind!=ArchiveRecordKind.HEARTS_ARCHIVE || archiveId!=null)
        require(kind!=ArchiveRecordKind.UNREVIEWED_MUSIC || archiveId==null)
    }
    val key:String get()=MessageDigest.getInstance("SHA-256").digest(
        listOf(localUserId,sourceDeviceId,sourceRecordId).joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString(""){"%02x".format(it)}
    fun toMap():Map<String,Any?> = linkedMapOf("schemaVersion" to schemaVersion,"localUserId" to localUserId,
        "sourceDeviceId" to sourceDeviceId,"sourceRecordId" to sourceRecordId,"revision" to revision,
        "driveSessionId" to driveSessionId,"musicId" to musicId,"playbackSessionId" to playbackSessionId,
        "recommendationId" to recommendationId,"archiveId" to archiveId,"updatedAt" to updatedAt,
        "kind" to kind.name,"title" to title,"artist" to artist,"syncState" to syncState.name,"deleted" to deleted)
}
enum class SyncAcceptance { APPLIED, DUPLICATE, STALE, CONFLICT, WRONG_USER, TRANSPORT_NOT_CONFIGURED }

/** Isolated inbox reducer. Does not write existing JSONL/Hearts or claim delivery to a server. */
class ArchiveSyncInbox(private val authorizedLocalUserId:String) {
    private val records=linkedMapOf<String,ArchiveSyncRecord>()
    @Synchronized fun acceptInIsolatedTest(incoming:ArchiveSyncRecord):SyncAcceptance {
        if(incoming.localUserId!=authorizedLocalUserId)return SyncAcceptance.WRONG_USER
        val next=incoming.copy(syncState=ArchiveSyncState.RECEIVED_IN_ISOLATED_TEST);val old=records[next.key]
        if(old!=null) {
            if(next.revision<old.revision)return SyncAcceptance.STALE
            if(next.revision==old.revision)return if(old==next)SyncAcceptance.DUPLICATE else SyncAcceptance.CONFLICT
        }
        records[next.key]=next;return SyncAcceptance.APPLIED
    }
    @Synchronized fun snapshot()=records.values.toList()
    fun restore(rows:List<ArchiveSyncRecord>)=rows.map(::acceptInIsolatedTest)
    fun sendToVehicleOrServer(@Suppress("UNUSED_PARAMETER") row:ArchiveSyncRecord)=SyncAcceptance.TRANSPORT_NOT_CONFIGURED
}
