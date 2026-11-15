package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.event.core.*
import com.example.gleo_type.event.location.LocationDto
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.navigation.DestinationDto
import com.example.gleo_type.event.TestJson
import com.example.gleo_type.realtime.RealtimePipeline
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

class ContextSnapshotTest {
    private class Rig {
        var now=0L;var drive:String?="drive"
        val pipeline=RealtimePipeline("run",{now})
        val speed=DrivingContextProvider("run",{drive},pipeline,{now},ContextInputSource.REPLAY,"isolated_test")
        val observations=ContextObservationStore{drive}
        val snapshots=ContextSnapshotProvider("user","run",observations,speed::current,{now},"isolated_test",
            {Instant.parse("2026-11-13T00:00:00Z")},{ZoneId.of("UTC")})
        init {pipeline.start("drive");speed.attach("owner");speed.connection("owner",CarConnectionSnapshot(CarConnectionState.CONNECTED,1))}
        fun warm() {(1..10).forEach {now=it*1000L;pipeline.speed("drive",0.0,now,"2026-11-13T00:00:00Z",null,null)
            observations.observeSpeed("drive",0.0,now,"2026-11-13T00:00:00Z")}}
        fun event(payload:EventPayload,ms:Long=now,id:String="event")=EventDto(id,"2026-11-13T00:00:00Z",drive,payload,
            EventObservation("2026-11-13T00:00:00Z",ms))
    }
    @Test fun actualExistingSpeedProcessorFeedsSnapshotWithoutInventingOtherContexts() {
        val r=Rig();r.warm();val s=r.snapshots.capture("drive")
        assertEquals(ContextFreshness.FRESH,s.driving.getValue("speedState").freshness)
        assertEquals("stopped",s.drivingInput.motionState)
        for(f in listOf(s.currentLocation.getValue("position"),s.destination.getValue("navigation"),
            s.road.getValue("congestionSegments"),s.timeWeather.getValue("weather"))) {
            assertNull(f.value);assertEquals(ContextFreshness.MISSING,f.freshness);assertNotNull(f.missingReason)
        }
        assertEquals("DEVICE_CLOCK",s.timeWeather.getValue("deviceTime").source)
        assertEquals(r.speed.current("drive"),s.drivingFor("user","drive",r.now))
    }
    @Test fun oldSpeedSnapshotCannotBeReusedAsFreshOrAcrossIdentity() {
        val cold=Rig()
        assertEquals(cold.speed.current("drive").fallbackReason,cold.snapshots.capture("drive").drivingFor("user","drive",cold.now).fallbackReason)
        val r=Rig();r.warm();val s=r.snapshots.capture("drive");r.now+=2501
        assertEquals("unknown",s.drivingFor("user","drive",r.now).quality)
        assertEquals(ContextFreshness.STALE,r.snapshots.capture("drive").driving.getValue("speedState").freshness)
        assertEquals("unknown",s.drivingFor("other","drive",10000).quality)
        assertEquals("unknown",s.drivingFor("user","other",10000).quality)
    }
    @Test fun declaredRainInRequestDoesNotBecomeApiWeather() {
        val r=Rig();r.warm()
        val v=VoiceCommand.testText("user","run","drive",CommandIntent.RECOMMEND_AND_PLAY,"잔잔한 음악 틀어 줘",
            "2026-11-13T00:00:00Z",r.now,mapOf("weather" to "rain"))
        val s=r.snapshots.capture("drive",v)
        assertEquals("USER_STATEMENT",s.voice.getValue("userClaims").origin)
        assertEquals(ContextFreshness.MISSING,s.timeWeather.getValue("weather").freshness)
        assertEquals(MusicRequestIntent.CALM,v.musicRequest!!.intent)
        assertEquals(v.voiceRequestId,s.voiceRequestId)
    }
    @Test fun locationDestinationAndEmptyTrafficKeepSourcesAndAges() {
        val r=Rig();r.warm();val summary=MusicSummaryDto(null,null,null)
        r.observations.observe(r.event(LocationPayload(summary,LocationDto("37.5","127.0",null,null,"10000")),id="location"))
        r.observations.observe(r.event(DestinationPayload(DestinationLabel.SET_DESTINATION,summary,
            DestinationDto("test","37.6","127.1",null,null,"123",null,null)),id="destination"))
        r.observations.observe(r.event(TrafficPayload(summary,emptyList()),id="road"))
        val s=r.snapshots.capture("drive")
        assertEquals("location",s.currentLocation.getValue("position").eventId)
        assertEquals(ContextFreshness.FRESH,s.destination.getValue("navigation").freshness)
        assertEquals(ContextFreshness.FRESH,s.road.getValue("congestionSegments").freshness)
        r.now+=16000;val old=r.snapshots.capture("drive")
        assertEquals(ContextFreshness.STALE,old.currentLocation.getValue("position").freshness)
        assertEquals(10000L,old.currentLocation.getValue("position").observedElapsedMs)
        assertEquals(ContextFreshness.STALE,old.road.getValue("congestionSegments").freshness)
    }
    @Test fun wrongSessionAndFutureTimestampAreNotObservationsForCurrentSnapshot() {
        val r=Rig();r.warm();val p=LocationPayload(MusicSummaryDto(null,null,null),LocationDto("37","127",null,null,null))
        r.observations.observe(r.event(p).copy(driveSessionId="other"))
        assertNull(r.snapshots.capture("drive").currentLocation.getValue("position").value)
        r.observations.observe(r.event(p,r.now+100,"future"))
        assertEquals(ContextFreshness.INVALID,r.snapshots.capture("drive").currentLocation.getValue("position").freshness)
    }
    @Test fun feedbackContractsKeepPauseNextNeutralAndWeightsUntrained() {
        for(k in listOf(FeedbackKind.PAUSE,FeedbackKind.NEXT))assertEquals("none",FeedbackContract.meaning(k).strength)
        assertEquals("positive",FeedbackContract.meaning(FeedbackKind.LIKE).polarity)
        assertEquals("negative",FeedbackContract.meaning(FeedbackKind.DISLIKE).polarity)
        assertEquals("CONTRACT_ONLY_NOT_APPLIED",FeedbackContract.meaning(FeedbackKind.SUFFICIENT_LISTEN).learningDisposition)
    }
    @Test fun apiWeatherNeedsProvenanceAndCancelledDestinationDoesNotStayActive() {
        val r=Rig();r.warm()
        r.observations.observe(r.event(DestinationPayload(DestinationLabel.CANCEL_DESTINATION,MusicSummaryDto(null,null,null),
            DestinationDto("old","37","127",null,null,null,null,"true"))))
        assertEquals("route_cancelled",r.snapshots.capture("drive").destination.getValue("navigation").missingReason)
        r.observations.observe(r.event(DestinationPayload(DestinationLabel.SET_DESTINATION,MusicSummaryDto(null,null,null),
            DestinationDto(null,null,null,null,null,null,null,null)),id="empty_destination"))
        assertEquals("destination_unavailable",r.snapshots.capture("drive").destination.getValue("navigation").missingReason)
        var weather=WeatherObservation("run","drive",ContextField(mapOf("condition" to "rain"),"TEST_WEATHER_API",
            "2026-11-13T00:00:00Z",r.now,ContextFreshness.FRESH,null,"isolated_test",origin="API_OBSERVATION"))
        val provider=ContextSnapshotProvider("user","run",r.observations,r.speed::current,{r.now},"isolated_test",
            weather=WeatherObservationPort {weather})
        assertEquals(ContextFreshness.FRESH,provider.capture("drive").timeWeather.getValue("weather").freshness)
        weather=weather.copy(field=weather.field.copy(source="USER_STATEMENT",origin="USER_STATEMENT"))
        assertEquals(ContextFreshness.INVALID,provider.capture("drive").timeWeather.getValue("weather").freshness)
        assertEquals("unknown",r.snapshots.capture("drive").drivingFor("user","drive",r.now,"other-run").quality)
    }
    @Test fun allFiveHundredSpeedScoresRemainIdenticalThroughSnapshot() {
        val path=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue("Existing model fixture required",path!=null)
        val dir=File(path!!);val j=TestJson.parse(dir.resolve("manifest.json").readText()) as Map<*,*>
        val tracks=(j["tracks"] as List<*>).map {row->val x=row as Map<*,*>;EmbeddingTrack(x["musicId"] as String,
            (x["genres"] as String).split('|').toSet(),(x["moods"] as String).split('|').toSet())}
        val m=MusicEmbeddingRepository.load(dir.resolve("embeddings.bin").readBytes(),tracks,j["modelVersion"] as String,j["sha256"] as String)
        val p=TestJson.parse(dir.resolve("parity.json").readText()) as Map<*,*>
        val preference=(p["preference"] as List<*>).map {(it as Number).toFloat()}.toFloatArray()
        val r=Rig();r.warm();val engine=DrivingRecommendationEngine(m)
        val before=engine.rank(preference,500,input=r.speed.current("drive"))
        val after=engine.rank(preference,500,input=r.snapshots.capture("drive").drivingFor("user","drive",r.now))
        assertEquals(before.scores,after.scores);assertTrue(after.applied);assertTrue(after.scores.any {it.contextCorrection>0})
    }
}
