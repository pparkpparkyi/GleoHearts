package com.example.gleo_type.internalfollowup

import com.example.gleo_type.internalai.*
import com.example.gleo_type.context.*
import com.example.gleo_type.event.vehicle.SpeedSampleRecorder
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProactiveAndSdkTest {
    private val f=InternalFixture
    private val zone=ZoneId.of("UTC")
    private fun planner()=ProactiveContextPlanner()
    private fun trips()=listOf("2026-11-09","2026-11-10","2026-11-11").mapIndexed{i,d->
        ObservedTrip("user","observed-$i","drive-$i","approved-work",Instant.parse("${d}T08:00:00Z").toEpochMilli(),"LIVE_SDK_DESTINATION_ARRIVAL",true)}
    private fun pattern()=planner().pattern("user",ConfirmedPlaceRole("user","approved-work","WORK",0),trips(),f.epoch,zone)
    private fun decide(s:ContextSnapshot=f.snapshot(),connected:Boolean=true,receipts:List<SuggestionReceipt> = emptyList(),now:Long=f.now)=
        planner().decide("user","run","drive",s,now,f.epoch,zone,connected,"approved-work",pattern(),receipts,allowReplay=true)
    @Test fun confirmedRepeatedCommuteAndObservedRainOnlyProduceSuggestion(){
        assertTrue(pattern().eligible);val p=decide();assertTrue(p.suggest);assertEquals("RAIN_COMMUTE",p.kind);assertFalse(p.automaticPlayback)
    }
    @Test fun missingOrUserClaimedWeatherIsNotRain(){
        val s=f.snapshot();val claimed=s.copy(timeWeather=s.timeWeather.mapValues{(k,v)->if(k=="weather")v.copy(origin="USER_STATEMENT") else v})
        assertEquals("COMMUTE",decide(claimed).kind)
        assertEquals("COMMUTE",decide(s.copy(timeWeather=emptyMap())).kind)
        assertFalse(decide(s.copy(timeWeather=mapOf("weather" to s.timeWeather.getValue("weather").copy(observedElapsedMs=0))),now=610001).suggest)
    }
    @Test fun unknownReconnectStaleAndWrongSessionFailClosed(){
        assertFalse(decide(connected=false).suggest);assertFalse(decide(now=f.now+2501).suggest)
        val s=f.snapshot();assertFalse(decide(s.copy(driveSessionId="other")).suggest)
        assertFalse(decide(s.copy(drivingInput=s.drivingInput.copy(quality="unknown",fallbackReason="reconnecting"))).suggest)
        assertFalse(decide(s.copy(drivingInput=s.drivingInput.copy(coverage=.1))).suggest)
        assertFalse(planner().decide("user","run","drive",s,f.now,f.epoch,zone,true,"approved-work",pattern(),emptyList()).suggest)
    }
    @Test fun timezoneAndWeekendDoNotBecomeMorningCommuteAndWallClockJumpsAreRejected(){
        val weekend=Instant.parse("2026-11-14T08:00:00Z").toEpochMilli()
        val s=f.snapshot().copy(createdAt="2026-11-14T08:00:00Z")
        val result=planner().decide("user","run","drive",s,f.now,weekend,zone,true,"approved-work",pattern(),emptyList(),allowReplay=true)
        assertEquals("RAIN_TRIP",result.kind)
        val localAfternoon=planner().decide("user","run","drive",f.snapshot(),f.now,f.epoch,ZoneId.of("Asia/Seoul"),true,"approved-work",pattern(),emptyList(),allowReplay=true)
        assertEquals("RAIN_TRIP",localAfternoon.kind)
        assertFalse(decide(f.snapshot().copy(createdAt="2026-11-14T08:00:00Z")).suggest)
    }
    @Test fun repeatTripsAreNotDistinctDaysAndUnconfirmedWorkIsNotInferred(){
        val role=ConfirmedPlaceRole("user","approved-work","WORK",0)
        assertFalse(planner().pattern("user",role,listOf(trips().first(),trips().first(),trips().first()),f.epoch,zone).eligible)
        assertFalse(planner().pattern("user",role.copy(role="UNKNOWN"),trips(),f.epoch,zone).eligible)
        assertFalse(planner().pattern("other",role,trips(),f.epoch,zone).eligible)
        assertFalse(planner().pattern("user",role,trips().map{it.copy(source="ISOLATED_TEST")},f.epoch,zone).eligible)
    }
    @Test fun rejectionCooldownFutureReceiptAndDifferentUserAreSeparated(){
        assertFalse(decide(receipts=listOf(SuggestionReceipt("user",f.epoch-1))).suggest)
        assertFalse(decide(receipts=listOf(SuggestionReceipt("user",f.epoch-900000,f.epoch+1000))).suggest)
        assertFalse(decide(receipts=listOf(SuggestionReceipt("user",f.epoch+1))).suggest)
        assertTrue(decide(receipts=listOf(SuggestionReceipt("other",f.epoch))).suggest)
    }
    @Test fun speedRecorderAcceptsUnchangedOneHzSamplesButRejectsDuplicatesAndReversedClock(){
        val received=mutableListOf<Long>();val recorder=SpeedSampleRecorder{_,at,_->received.add(at)}
        recorder.accept(10f,1000,null);recorder.accept(10f,1500,null);recorder.accept(10f,2000,null)
        recorder.accept(10f,1999,null);recorder.accept(Float.NaN,3000,null)
        assertEquals(listOf(1000L,2000L),received)
        recorder.reset();recorder.accept(10f,100,null);assertEquals(100L,received.last())
    }
}
