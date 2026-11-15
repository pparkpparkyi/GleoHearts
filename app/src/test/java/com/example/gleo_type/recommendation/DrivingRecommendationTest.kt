package com.example.gleo_type.recommendation

import com.example.gleo_type.realtime.*
import com.example.gleo_type.vehicle.*
import com.example.gleo_type.event.TestJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DrivingRecommendationTest {
    private class Rig(val source:ContextInputSource=ContextInputSource.LIVE_SDK) {
        var now=0L;var drive:String?="drive"
        val p=RealtimePipeline("run",{now})
        val provider=DrivingContextProvider("run",{drive},p,{now},source)
        init { p.start("drive");provider.attach("owner") }
        fun state(state:CarConnectionState,token:Long=1)=provider.connection("owner",CarConnectionSnapshot(state,token))
        fun speed(at:Long,value:Double=10.0) { now=at;p.speed("drive",value,now,"received",null,null) }
        fun warm(value:Double=10.0) { state(CarConnectionState.CONNECTED);(1..10).forEach { speed(it*1000L,value) } }
        fun get()=provider.current("drive")
    }
    @Test fun liveNormalSpeedRegimesAndObservationFields() {
        for((speed,motion) in listOf(0.0 to "stopped",10.0 to "low_speed",50.0 to "moving")) {
            val r=Rig();r.warm(speed);val i=r.get()
            assertEquals("available",i.quality);assertEquals(motion,i.motionState)
            assertEquals(speed,i.averageSpeedKmh!!,1e-9);assertTrue(i.observedDurationMs>=6000)
            assertEquals(10000L,i.latestObservedElapsedMs);assertEquals(ContextInputSource.LIVE_SDK,i.source)
        }
    }
    @Test fun disconnectedAndRetryStatesAlwaysFallback() {
        val r=Rig();r.warm()
        for(s in listOf(CarConnectionState.STOPPED,CarConnectionState.RETRY_WAIT,CarConnectionState.CONNECTING,
            CarConnectionState.EXHAUSTED,CarConnectionState.BLOCKED,CarConnectionState.RELEASED)) {
            r.state(s);assertEquals("transport_unavailable",r.get().fallbackReason)
        }
    }
    @Test fun reconnectCannotReuseOldWindowAndFreshDataRestoresContext() {
        val r=Rig();r.warm();assertEquals("available",r.get().quality)
        r.state(CarConnectionState.RETRY_WAIT,2);r.state(CarConnectionState.CONNECTED,3)
        assertEquals("unknown",r.get().quality)
        r.speed(11000);assertEquals("unknown",r.get().quality)
        (12..18).forEach { r.speed(it*1000L) };assertEquals("available",r.get().quality)
    }
    @Test fun staleMissingAndUnknownSamplesFallback() {
        val r=Rig();r.state(CarConnectionState.CONNECTED);assertEquals("unknown",r.get().quality)
        r.warm();r.now+=2501;assertEquals("stale_speed",r.get().fallbackReason)
        r.p.stop("drive");assertEquals("context_unknown",r.get().fallbackReason)
    }
    @Test fun sessionRunAndOwnerIsolation() {
        val r=Rig();r.warm();assertEquals("inactive_or_wrong_drive",r.provider.current("other").fallbackReason)
        r.drive="second";assertEquals("connection_session_mismatch",r.provider.current("second").fallbackReason)
        r.provider.attach("new-owner");r.provider.connection("owner",CarConnectionSnapshot(CarConnectionState.CONNECTED,9))
        assertEquals("transport_unavailable",r.provider.current("second").fallbackReason)
        val wrongRun=DrivingContextProvider("other-run",{"drive"},r.p,{r.now})
        r.drive="drive";wrongRun.attach("x");wrongRun.connection("x",CarConnectionSnapshot(CarConnectionState.CONNECTED))
        assertEquals("run_or_drive_mismatch",wrongRun.current("drive").fallbackReason)
    }
    @Test fun replaySourceRemainsExplicitAndClockReversalIsUnknown() {
        val r=Rig(ContextInputSource.REPLAY);r.warm();assertEquals(ContextInputSource.REPLAY,r.get().source)
        r.now=9000;assertEquals("untrusted_timestamp",r.get().fallbackReason)
    }
    @Test fun duplicateReadyDoesNotResetWatermarkButNewTokenDoes() {
        val r=Rig();r.warm();r.state(CarConnectionState.CONNECTED)
        assertEquals("available",r.get().quality)
        r.state(CarConnectionState.CONNECTED,5);assertEquals("unknown",r.get().quality)
    }
    private fun model():Pair<MusicEmbeddingRepository,FloatArray> {
        val path=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue("Real model required",path!=null)
        val dir=File(path!!);val m=TestJson.parse(dir.resolve("manifest.json").readText()) as Map<*,*>
        val tracks=(m["tracks"] as List<*>).map { row -> val r=row as Map<*,*>
            EmbeddingTrack(r["musicId"] as String,(r["genres"] as String).split('|').toSet(),(r["moods"] as String).split('|').toSet()) }
        val repo=MusicEmbeddingRepository.load(dir.resolve("embeddings.bin").readBytes(),tracks,m["modelVersion"] as String,m["sha256"] as String)
        val parity=TestJson.parse(dir.resolve("parity.json").readText()) as Map<*,*>
        return repo to (parity["preference"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
    }
    @Test fun allFallbacksExactlyPreserveRealFiveHundredScoresAndTopTen() {
        val(m,p)=model();val original=ClapRecommendationEngine(m).rank(p,500)
        val r=Rig();r.warm()
        for(reason in listOf("transport_unavailable","insufficient_observation","stale_speed","context_unknown","untrusted_timestamp")) {
            val input=r.get().copy(quality="unknown",fallbackReason=reason)
            val result=DrivingRecommendationEngine(m).rank(p,500,input=input)
            assertFalse(result.applied);assertEquals(original.map { it.musicId },result.scores.map { it.musicId })
            assertEquals(original.map { it.score },result.scores.map { it.finalScore })
        }
        val disabled=DrivingRecommendationEngine(m,DrivingScorePolicy(0.0)).rank(p,10,input=r.get())
        assertEquals(original.take(10).map { it.score },disabled.scores.map { it.finalScore })
    }
    @Test fun validContextAppliesOnlyDisclosedFiniteBonusAndFinalTopN() {
        val(m,p)=model();val r=Rig();r.warm(0.0)
        val result=DrivingRecommendationEngine(m).rank(p,500,input=r.get())
        assertTrue(result.applied);assertNull(result.fallbackReason)
        val tracks=m.tracks.associateBy { it.musicId }
        assertTrue(result.scores.any { it.contextCorrection>0 })
        result.scores.forEach { s ->
            assertEquals(.05*DrivingScorePolicy().bonus(tracks.getValue(s.musicId),"stopped"),s.contextCorrection,0.0)
            assertEquals(s.preferenceScore+s.contextCorrection,s.finalScore,0.0)
        }
        assertEquals(result.scores.take(10),DrivingRecommendationEngine(m).rank(p,10,input=r.get()).scores)
        System.getenv("GLEO_DRIVING_PARITY_OUTPUT")?.let { path ->
            val rows=result.scores.joinToString(",") { s -> "\"${s.musicId}\":{\"preference\":${s.preferenceScore},\"correction\":${s.contextCorrection},\"final\":${s.finalScore}}" }
            val ids=result.scores.take(10).joinToString(",") { "\"${it.musicId}\"" }
            File(path).writeText("{\"source\":\"real_model_synthetic_stopped_context\",\"scores\":{$rows},\"top10\":[$ids]}")
        }
    }
    @Test fun filtersStayWithinVerifiedPlayableIdsAndDoNotMutatePreference() {
        val(m,p)=model();val copy=p.copyOf();val r=Rig();r.warm()
        val ids=m.tracks.take(3).map { it.musicId }
        val result=DrivingRecommendationEngine(m).rank(p,10,setOf(ids[0]),setOf(ids[1]),ids.toSet(),r.get())
        assertEquals(listOf(ids[2]),result.scores.map { it.musicId });assertArrayEquals(copy,p,0f)
        assertTrue(DrivingRecommendationEngine(m).rank(p,10,playableIds=setOf("music_001","jamendo_0010"),input=r.get()).scores.isEmpty())
    }
    @Test fun malformedContextAndInvalidWeightsCannotInfluenceRanking() {
        val(m,p)=model();val r=Rig();r.warm()
        for(input in listOf(r.get().copy(averageSpeedKmh=Double.NaN),r.get().copy(coverage=Double.NaN),
            r.get().copy(latestObservedElapsedMs=20000),r.get().copy(motionState="traffic_jam")))
            assertFalse(DrivingRecommendationEngine(m).rank(p,input=input).applied)
        for(w in listOf(-.01,Double.NaN,.21)) assertTrue(runCatching { DrivingScorePolicy(w) }.isFailure)
    }
}
