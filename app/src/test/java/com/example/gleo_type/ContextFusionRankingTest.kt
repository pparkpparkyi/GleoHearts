package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.event.TestJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

class ContextFusionRankingTest {
    private val now=10_000L
    private fun model(): MusicEmbeddingRepository {
        val path=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue(path!=null)
        val dir=File(path!!);val j=TestJson.parse(dir.resolve("manifest.json").readText()) as Map<*,*>
        val tracks=(j["tracks"] as List<*>).map { val r=it as Map<*,*>;EmbeddingTrack(r["musicId"] as String,
            (r["genres"] as String).split('|').toSet(),(r["moods"] as String).split('|').toSet()) }
        return MusicEmbeddingRepository.load(dir.resolve("embeddings.bin").readBytes(),tracks,j["modelVersion"] as String,j["sha256"] as String)
    }
    private fun snapshot():ContextSnapshot {
        val input=DrivingRecommendationInput("run","drive",now,null,"unknown","not_observed",environment="isolated_test")
        return ContextSnapshotProvider("user","run",ContextObservationStore{"drive"},{input},{now},"isolated_test",
            {Instant.parse("2026-11-13T12:00:00Z")},{ZoneId.of("UTC")}).capture("drive")
    }
    private fun encoder(): LocalClapTextEncoder {
        val path=System.getenv("GLEO_CLAP_TEXT_EXPERIMENT");assumeTrue("Real PC inference evidence required",path!=null)
        val j=TestJson.parse(File(path!!).readText()) as Map<*,*>
        val rows=j["results"] as List<*>
        return LocalClapTextEncoder { text -> rows.map { it as Map<*,*> }.singleOrNull { it["text"]==text }?.let { r ->
            TextVector((r["vector"] as List<*>).map{(it as Number).toFloat()}.toFloatArray(),j["modelId"] as String,
                j["revision"] as String,text,r["language"] as String,(r["inferenceMs"] as Number).toDouble()) } }
    }
    private fun record(name:String,value:Any?) {
        val path=System.getenv("GLEO_FUSION_RESULTS") ?: return
        val dir=File(path);dir.mkdirs();dir.resolve("$name.json").writeText(json(value))
    }
    private fun json(v:Any?):String=when(v) {
        null -> "null"
        is Map<*,*> -> v.entries.joinToString(",","{","}"){json(it.key.toString())+":"+json(it.value)}
        is Iterable<*> -> v.joinToString(",","[","]"){json(it)}
        is Number,is Boolean -> v.toString()
        else -> "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""
    }
    private fun field(value:Any?,origin:String="OBSERVATION")=ContextField(value,"ISOLATED_TEST",null,now,ContextFreshness.FRESH,null,"isolated_test",origin=origin)
    @Test fun A_realTextChangesFiveHundredScoresWithoutKeywordMapping() {
        val m=model();val p=m.onboarding(setOf("pop"),setOf("happy"));val s=snapshot();val ranker=ContextFusionRanker(m,encoder())
        val a=ranker.rank("user",p,s,now,1_000_000,"잔잔하고 몽환적인 음악 틀어줘",topK=500)
        val b=ranker.rank("user",p,s,now,1_000_000,"신나고 활기찬 음악 틀어줘",topK=500)
        val none=ranker.rank("user",p,s,now,1_000_000,null,topK=500)
        assertEquals("REAL_LOCAL_CLAP_TEXT",a.textStatus);assertEquals(500,a.scores.size)
        assertTrue(a.scores.any { x -> kotlin.math.abs(x.total-b.scores.single{it.musicId==x.musicId}.total)>1e-6 })
        assertTrue(none.scores.all { it.textCosine==null && it.contributions.getValue("voice")==0.0 })
        record("A",mapOf("source" to "PC_REAL_CLAP_FIXTURE_NOT_ANDROID_INFERENCE","calm" to a.toMap(),"energetic" to b.toMap(),"none" to none.toMap()))
    }
    @Test fun B_contextUsesFreshObservationsAndSpeedExactlyOnce() {
        val m=model();val p=m.onboarding(setOf("pop"),setOf("happy"));val base=snapshot()
        val speed=base.drivingInput.copy(quality="available",fallbackReason=null,averageSpeedKmh=0.0,motionState="stopped",
            observedDurationMs=8000,coverage=1.0,latestObservedElapsedMs=now)
        val changed=base.copy(drivingInput=speed,driving=mapOf("speedState" to field("stopped")),
            timeWeather=mapOf("deviceTime" to field(mapOf("hour" to 2)),"weather" to field(mapOf("condition" to "rain"),"API_OBSERVATION")),
            destination=mapOf("navigation" to field(mapOf("latitude" to "37.5","longitude" to "127.0"))))
        val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
        val a=ranker.rank("user",p,base,now,1_000_000,null,topK=500)
        val b=ranker.rank("user",p,changed,now,1_000_000,null,topK=500)
        assertTrue(b.scores.any {it.contributions.getValue("time")>0 && it.contributions.getValue("weather")>0})
        b.scores.forEach {row->assertEquals(b.legacySpeed.scores.single{it.musicId==row.musicId}.contextCorrection,row.contributions.getValue("driving"),0.0)}
        val claim=changed.copy(timeWeather=changed.timeWeather+ ("weather" to field(mapOf("condition" to "rain"),"USER_STATEMENT")))
        assertTrue(ranker.rank("user",p,claim,now,1_000_000,null,topK=500).scores.all{it.contributions.getValue("weather")==0.0})
        val stale=ranker.rank("user",p,changed,now+600_001,1_000_000,null,topK=500)
        assertTrue(stale.scores.all{it.contributions.filterKeys{k->k in setOf("driving","time","weather")}.values.all{v->v==0.0}})
        val singleChanges=mapOf("speedOnly" to base.copy(drivingInput=speed,driving=changed.driving),
            "timeOnly" to base.copy(timeWeather=base.timeWeather+("deviceTime" to field(mapOf("hour" to 2)))),
            "weatherOnly" to base.copy(timeWeather=base.timeWeather+("weather" to field(mapOf("condition" to "rain"),"API_OBSERVATION"))))
            .mapValues { ranker.rank("user",p,it.value,now,1_000_000,null,topK=500) }
        assertTrue(singleChanges.getValue("speedOnly").scores.any { it.contributions.getValue("driving")>0 })
        assertTrue(singleChanges.getValue("timeOnly").scores.all { it.contributions.getValue("weather")==0.0 })
        assertTrue(singleChanges.getValue("weatherOnly").scores.all { it.contributions.getValue("time")==0.0 })
        record("B",mapOf("source" to "ISOLATED_SYNTHETIC_CONTEXT","dayWeatherMissing" to a.toMap(),"nightRainStoppedDestination" to b.toMap(),
            "stale" to stale.toMap(),"individualChanges" to singleChanges.mapValues{it.value.toMap()}))
    }
    @Test fun C_casesAreUserScopedAgedAndMissingAware() {
        val m=model();val s=snapshot();val p=m.onboarding(setOf("pop"),setOf("happy"));val f=ContextFeatureExtractor.extract(s,now)
        val target=m.tracks.first().musicId
        val case=ExperienceCase("isolated-case","user",target,"isolated-playback",f,1.0,setOf("isolated-feedback"),1_000_000,"ISOLATED_TEST")
        val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
        val a=ranker.rank("user",p,s,now,1_000_000,null,topK=500)
        val b=ranker.rank("user",p,s,now,1_000_000,null,listOf(case),topK=500)
        assertEquals(.15/6,b.scores.single{it.musicId==target}.total-a.scores.single{it.musicId==target}.total,1e-12)
        assertTrue(CaseBasedReasoner.retrieve("other",f,listOf(case),1_000_000).isEmpty())
        assertTrue(CaseBasedReasoner.retrieve("user",f,listOf(case),Long.MAX_VALUE).isEmpty())
        assertEquals(0.0,ObservedFeatures(emptyMap()).similarity(ObservedFeatures(emptyMap())),0.0)
        record("C",mapOf("source" to "ISOLATED_TEST_CASE_NOT_USER_HISTORY","without" to a.toMap(),"with" to b.toMap()))
    }
    @Test fun D_profileChangesScoresAndRanking() {
        val m=model();val s=snapshot();val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
        val a=ranker.rank("user",m.vector(m.tracks[0].musicId)!!,s,now,1_000_000,null,topK=500)
        val b=ranker.rank("user",m.vector(m.tracks[499].musicId)!!,s,now,1_000_000,null,topK=500)
        assertEquals(m.tracks[0].musicId,a.scores.first().musicId);assertEquals(m.tracks[499].musicId,b.scores.first().musicId)
        record("D",mapOf("source" to "ISOLATED_PROFILE_VECTORS_NOT_TRAINED_USERS","profileA" to a.toMap(),"profileB" to b.toMap()))
    }
    @Test fun locationDestinationRoadContributeOnlyThroughMatchedCbrEvidence() {
        val m=model();val base=snapshot();val target=m.tracks.first().musicId;val pref=m.vector(target)!!
        val variants=mapOf("location" to base.copy(currentLocation=mapOf("position" to field(mapOf("latitude" to "37.5","longitude" to "127.0")))),
            "destination" to base.copy(destination=mapOf("navigation" to field(mapOf("latitude" to "37.6","longitude" to "127.1")))),
            "road" to base.copy(road=mapOf("congestionSegments" to field(listOf(mapOf("status" to "congested"))))))
        val records=variants.mapValues { (facet,s) ->
            val case=ExperienceCase("isolated-$facet","user",target,"isolated-pb",ContextFeatureExtractor.extract(s,now),1.0,
                setOf("isolated-feedback"),1_000_000,"ISOLATED_TEST")
            val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
            val with=ranker.rank("user",pref,s,now,1_000_000,null,listOf(case),topK=500)
            val without=ranker.rank("user",pref,base,now,1_000_000,null,listOf(case),topK=500)
            assertTrue(with.scores.single{it.musicId==target}.cbr>without.scores.single{it.musicId==target}.cbr)
            assertTrue(with.scores.all{it.contributions.getValue("${facet}Direct")==0.0})
            mapOf("matched" to with.toMap(),"missing" to without.toMap())
        }
        record("B-location-destination-road",records)
    }
    @Test fun E_filtersCanonicalIdsBeforeRankingAndScoresSum() {
        val m=model();val s=snapshot();val ids=setOf("jamendo_api_1204669","jamendo_api_1141572","jamendo_api_1161940")
        val decision=ContextFusionRanker(m,encoder()).rank("user",m.vector(m.tracks.first().musicId)!!,s,now,1_000_000,
            "잔잔하고 몽환적인 음악 틀어줘",playable=ids,excluded=setOf(ids.first()),topK=500)
        assertEquals(ids-ids.first(),decision.scores.map{it.musicId}.toSet())
        decision.scores.forEach {assertEquals(it.contributions.values.sum(),it.total,0.0)}
        record("E",mapOf("source" to "REAL_MODEL_PLUS_CANONICAL_PLAYABLE_FILTER","result" to decision.toMap()))
    }
    @Test fun G_failedOrIncompatibleEncoderNeverUsesFakeTextVectors() {
        val m=model();val s=snapshot();val p=m.vector(m.tracks.first().musicId)!!
        for(encoder in listOf(LocalClapTextEncoder{null},LocalClapTextEncoder{error("unavailable")},LocalClapTextEncoder{
            TextVector(FloatArray(512),"wrong-model","0".repeat(40),it,"ko",0.0)})) {
            val r=ContextFusionRanker(m,encoder).rank("user",p,s,now,1_000_000,"test",topK=500)
            assertTrue(r.textStatus in setOf("ENCODER_UNAVAILABLE","ENCODER_FAILED_OR_INCOMPATIBLE"))
            assertTrue(r.scores.all{it.textCosine==null && it.contributions.getValue("voice")==0.0})
            assertEquals(ClapRecommendationEngine(m).rank(p,500).map{it.musicId},r.scores.map{it.musicId})
            record("G-${r.textStatus}",r.toMap())
        }
    }
}
