package com.example.gleo_type.ui.app

import com.example.gleo_type.vehicle.CarConnectionSnapshot
import com.example.gleo_type.vehicle.CarConnectionState
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** Platform UX policy, never zero-speed inference. A new connection needs a new UX observation. */
class HeartsEditGate {
    private var token:Long?=null
    private var connectedSince=Long.MAX_VALUE
    @Synchronized fun allowed(ux:DrivingUx,connection:CarConnectionSnapshot?,now:Long,intentionalCollectionStop:Boolean=false):Boolean {
        // Stopping telemetry by explicit UI action does not stop the independent platform UX manager.
        // This exception requires a *new* platform UX reading after that intentional stop.
        val cleanStop=intentionalCollectionStop && connection?.state==CarConnectionState.STOPPED && connection.reason==null
        if(connection?.state!=CarConnectionState.CONNECTED && !cleanStop){token=null;connectedSince=Long.MAX_VALUE;return false}
        val generation=if(cleanStop) -connection!!.token-1 else connection!!.token
        if(token!=generation){token=generation;connectedSince=now;return false}
        val at=ux.observedElapsedMs ?: return false
        return ux.source=="CarUxRestrictionsManager" && ux.complexAllowed && at>=connectedSince && now-at in 0..6000
    }
}

/** Adapter only: existing history and archive own persistence, not Compose or the player. */
class HeartsReviewSaver(private val archives:ArchiveRepository,private val history:DriveHistoryRepository,
    private val localId:String,private val catalog:()->List<Track>,private val allowed:()->Boolean,
    private val now:()->String={Instant.now().toString()},private val zone:()->String={ZoneId.systemDefault().id}) {
    private val busy=AtomicBoolean(false)
    fun save(key:String,included:List<String>,media:ArchiveMediaDraft?=null):CarV10SaveResult {
        if(!busy.compareAndSet(false,true))return CarV10SaveResult(null,"저장 진행 중")
        return try {
            check(allowed()){ "Editing restricted" }
            val snapshot=history.refresh();check(snapshot.error==null){"History unavailable"}
            val record=snapshot.drives.single { it.key==key }
            check(allowed()){ "Editing restricted" }
            val saved=if(media==null)archives.saveReview(record,catalog(),included,localId,zone(),now())
                else archives.saveReviewWithMedia(record,catalog(),included,localId,zone(),now(),media)
            val actual=archives.load().filter { p -> saved.any { it.id==p.id } }
            check(actual.size==saved.size && actual.all { it.heartsReview?.includedMusicIds==included })
            CarV10SaveResult(saved.joinToString(","){it.id},"gleo Hearts에 저장했습니다 · 취향 학습은 별도")
        } catch(_:Exception) { CarV10SaveResult(null,"저장 실패 또는 편집 제한 · 선택과 원본 유지 · 다시 시도해 주세요") }
        finally { busy.set(false) }
    }
}
