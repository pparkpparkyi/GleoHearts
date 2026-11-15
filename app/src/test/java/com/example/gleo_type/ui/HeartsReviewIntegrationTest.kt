package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeartsReviewIntegrationTest {
    private val record=DriveRecord("run/drive","drive","2026-11-11T23:59:00Z","2026-11-12T00:01:00Z",listOf("a","b"),1000.0,true,null,true)
    private class Store:ArchiveRepository {
        var fail=false;var writes=0;var before:()->Unit={};var pins=emptyList<Pin>()
        override fun load()=pins
        override fun save(pin:Pin){}
        override fun delete(id:String){}
        override fun saveReview(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String):List<Pin> {
            before();check(!fail);require(record.endedAt!=null && included.isNotEmpty() && included.all { it in record.tracks })
            writes++;pins=included.map { ArchiveProjection.pinFromDrive(record,Track(it,it,""),emptyList(),"",at).copy(
                heartsReview=HeartsReviewMetadata(localId,record.endedAt!!,zone,included,record.tracks-included.toSet(),emptyList(),emptySet(),emptySet())) };return pins
        }
    }
    private fun saver(store:Store,drive:DriveRecord=record,safe:()->Boolean={true})=HeartsReviewSaver(store,object:DriveHistoryRepository {
        override fun refresh()=HistorySnapshot(listOf(drive))
    },"local",{emptyList()},safe,{"2026-11-12T01:00:00Z"},{"Asia/Seoul"})
    @Test fun connectionGenerationNeedsNewPlatformUxAndNeverSpeed() {
        val gate=HeartsEditGate();val c=CarConnectionSnapshot(CarConnectionState.CONNECTED,1)
        val ux=DrivingUx(true,false,0,"CarUxRestrictionsManager",100)
        assertFalse(gate.allowed(ux,c,100));assertTrue(gate.allowed(ux.copy(observedElapsedMs=101),c,102))
        assertFalse(gate.allowed(ux,c.copy(state=CarConnectionState.RETRY_WAIT),103))
        assertFalse(gate.allowed(ux,c.copy(token=2),104));assertFalse(gate.allowed(ux,c.copy(token=2),105))
        assertTrue(gate.allowed(ux.copy(observedElapsedMs=106),c.copy(token=2),107))
        assertFalse(gate.allowed(ux.copy(observedElapsedMs=106),c.copy(token=2),7000))
        assertFalse(gate.allowed(ux.copy(source="speed_zero",observedElapsedMs=7001),c.copy(token=2),7002))
    }
    @Test fun intentionalTelemetryStopStillNeedsFreshIndependentPlatformPermission() {
        val gate=HeartsEditGate();val stop=CarConnectionSnapshot(CarConnectionState.STOPPED,3)
        val ux=DrivingUx(true,false,0,"CarUxRestrictionsManager",100)
        assertFalse(gate.allowed(ux,stop,101));assertFalse(gate.allowed(ux,stop,102,true))
        assertFalse(gate.allowed(ux,stop,103,true))
        assertTrue(gate.allowed(ux.copy(observedElapsedMs=104),stop,105,true))
        assertFalse(gate.allowed(ux.copy(observedElapsedMs=106),stop.copy(state=CarConnectionState.RETRY_WAIT),107,true))
    }
    @Test fun saveFailureRetryKeepsSelectionAndDoesNotReportPreferenceLearning() {
        val store=Store();val s=saver(store);store.fail=true
        assertNull(s.save(record.key,listOf("a")).persistedReference);assertEquals(0,store.writes)
        store.fail=false;val result=s.save(record.key,listOf("a"));assertNotNull(result.persistedReference)
        assertTrue(result.message.contains("별도"));assertEquals(listOf("b"),store.load().single().heartsReview!!.excludedMusicIds)
    }
    @Test fun missingEndUnknownAndEmptyCannotSave() {
        val store=Store()
        assertNull(saver(store,record.copy(endedAt=null)).save(record.key,listOf("a")).persistedReference)
        assertNull(saver(store,safe={false}).save(record.key,listOf("a")).persistedReference)
        assertNull(saver(store).save(record.key,emptyList()).persistedReference);assertEquals(0,store.writes)
    }
    @Test fun simultaneousClicksCannotExecuteTwoSaves() {
        val store=Store();val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        store.before={entered.countDown();check(finish.await(5,TimeUnit.SECONDS))}
        val executor=Executors.newSingleThreadExecutor();val s=saver(store)
        try {val first=executor.submit<CarV10SaveResult>{s.save(record.key,listOf("a"))}
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertNull(s.save(record.key,listOf("a")).persistedReference)
            finish.countDown();assertNotNull(first.get(5,TimeUnit.SECONDS).persistedReference);assertEquals(1,store.writes)
        } finally {finish.countDown();executor.shutdownNow()}
    }
}
