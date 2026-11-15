package com.example.gleo_type.internalfollowup

import com.example.gleo_type.context.*
import com.example.gleo_type.internalai.*
import com.example.gleo_type.recommendation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.*

internal object InternalFixture {
    const val now=10000L
    val epoch=Instant.parse("2026-11-13T08:00:00Z").toEpochMilli()
    val model by lazy {
        org.junit.Assume.assumeTrue("Private licensed model not supplied",System.getenv("GLEO_CLAP_MODEL_DIR")!=null)
        val root=File(requireNotNull(System.getenv("GLEO_CLAP_MODEL_DIR")))
        val j=JSONObject(File(root,"manifest.json").readText());val rows=j.getJSONArray("tracks")
        val tracks=(0 until rows.length()).map{rows.getJSONObject(it).let{r->EmbeddingTrack(r.getString("musicId"),r.getString("genres").split('|').toSet(),r.getString("moods").split('|').toSet())}}
        MusicEmbeddingRepository.load(File(root,"embeddings.bin").readBytes(),tracks,j.getString("modelVersion"),j.getString("sha256"))
    }
    fun field(value:Any?,origin:String="OBSERVATION")=ContextField(value,"ISOLATED_FIXTURE",null,9000,ContextFreshness.FRESH,null,"isolated_test",origin=origin)
    fun snapshot(user:String="user")=ContextSnapshot("snapshot",user,"run","drive",null,"2026-11-13T08:00:00Z",now,"isolated_test",
        mapOf("position" to field(mapOf("latitude" to 1.0,"longitude" to 2.0))),
        mapOf("navigation" to field(mapOf("name" to "synthetic-workplace"))),
        mapOf("speedState" to field(mapOf("motionState" to "moving"))),
        mapOf("traffic" to field(mapOf("state" to "synthetic-clear"))),
        mapOf("deviceTime" to field(mapOf("hour" to 8)),"weather" to field(mapOf("condition" to "rain"),"API_OBSERVATION")),
        mapOf("command" to field(mapOf("musicIntent" to "ENERGETIC"),"COMMAND")),
        DrivingRecommendationInput("run","drive",now,1,"available",null,30.0,false,false,"moving",8000,.9,9500,source=ContextInputSource.REPLAY,environment="isolated_test"),"fixture")
    fun case(id:String,music:String,user:String="user",reward:Double=1.0,pb:String="pb-$id")=ExperienceCase(id,user,music,pb,
        ContextFeatureExtractor.extract(snapshot(user),now),reward,setOf("feedback-$id"),epoch,"UI_EXPLICIT_LEDGER_FEEDBACK","snapshot","recommendation","drive")
    fun proof(c:ExperienceCase)=ExpressionEvidenceProof(c.caseId,model.fingerprint,"run",ExpressionEvidenceEnvironment.ISOLATED_TEST,true,true)
}

class InternalAiTest {
    private val f=InternalFixture
    @Test fun exactRankerArithmeticAndSixFacetAblationsUseFrozenInput(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"));val s=f.snapshot()
        val cases=listOf(f.case("a",m.tracks[0].musicId))
        val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
        val first=ContextContributionAudit.inspect(ranker,"user","run","drive",p,s,f.now,f.epoch,null,cases)
        val second=ContextContributionAudit.inspect(ranker,"user","run","drive",p,s,f.now,f.epoch,null,cases)
        assertEquals(first,second);assertEquals(10,first.size)
        assertEquals(setOf("location","destination","driving","road","timeWeather","voice"),first.first().removals.map{it.facet}.toSet())
        first.forEach{assertEquals(it.score,it.arithmeticParts.values.sum(),1e-9)}
        val target=ContextContributionAudit.inspect(ranker,"user","run","drive",p,s,f.now,f.epoch,null,cases,topN=500).single{it.musicId==m.tracks[0].musicId}
        assertTrue(target.removals.single{it.facet=="location"}.scoreDelta>0)
        assertTrue(target.removals.all{it.scoreDelta>0}) // all six synthetic observed facets affect CBR similarity
        val out=System.getenv("GLEO_INTERNAL_RESULTS")?.let{File(it)} ?: return
        out.mkdirs();File(out,"context-contributions.json").writeText(org.json.JSONArray(first.map{r->JSONObject().put("musicId",r.musicId).put("score",r.score).put("parts",JSONObject(r.arithmeticParts))
            .put("removals",org.json.JSONArray(r.removals.map{JSONObject().put("facet",it.facet).put("delta",it.scoreDelta).put("rankWithout",it.rankWithout)}))}).toString(2))
    }
    @Test fun unknownAndStaleDataDoNotBecomeFreshContributions(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"));val s=f.snapshot()
        val stale=s.copy(drivingInput=s.drivingInput.copy(quality="unknown",fallbackReason="disconnected"),
            currentLocation=s.currentLocation.mapValues{it.value.copy(freshness=ContextFreshness.STALE)},
            destination=emptyMap(),road=emptyMap(),timeWeather=emptyMap(),voice=emptyMap())
        val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null});val audit=ContextContributionAudit.inspect(ranker,"user","run","drive",p,stale,f.now,f.epoch,null,emptyList())
        assertTrue(audit.all{it.arithmeticParts["driving"]==0.0&&it.arithmeticParts["cbr"]==0.0})
        assertTrue(audit.flatMap{it.removals}.all{it.scoreDelta==0.0})
    }
    @Test fun realCuratedClapVoiceVectorsActuallyContributeWithoutClaimingOnDeviceTextInference(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"))
        val encoder=CuratedMusicTextEncoder(File("app/src/main/assets/clap-mood-prototypes-v1.json").readText())
        val ranker=ContextFusionRanker(m,encoder)
        val decision=ranker.rank("user",p,f.snapshot(),f.now,f.epoch,"신나는 노래 틀어줘",emptyList())
        assertEquals("REAL_CLAP_PRECOMPUTED_CURATED_ENGLISH",decision.textStatus)
        val rows=ContextContributionAudit.inspect(ranker,"user","run","drive",p,f.snapshot(),f.now,f.epoch,"신나는 노래 틀어줘",emptyList())
        assertTrue(rows.any{kotlin.math.abs(it.arithmeticParts.getValue("voice"))>1e-6})
        rows.forEach{row->assertEquals(row.arithmeticParts.getValue("voice"),row.removals.single{it.facet=="voice"}.scoreDelta,1e-9)}
        System.getenv("GLEO_INTERNAL_RESULTS")?.let{out->File(out,"clap-voice-contributions.json").writeText(JSONObject()
            .put("scope","ISOLATED_CONTEXT_REAL_PRECOMPUTED_CLAP_VECTORS_NOT_DEVICE_TEXT_INFERENCE")
            .put("textStatus",decision.textStatus).put("historicalAudioCheckpointIdentityVerified",false)
            .put("scores",org.json.JSONArray(rows.map{JSONObject().put("musicId",it.musicId).put("score",it.score).put("parts",JSONObject(it.arithmeticParts))})).toString(2))}
    }
    @Test fun observedNightRainBonusesAreNotGeneratedFromUserWeatherClaims(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"));val s=f.snapshot().copy(timeWeather=mapOf(
            "deviceTime" to f.field(mapOf("hour" to 5)),"weather" to f.field(mapOf("condition" to "rain"),"API_OBSERVATION")))
        val ranker=ContextFusionRanker(m,LocalClapTextEncoder{null})
        val observed=ranker.rank("user",p,s,f.now,f.epoch,null,topK=500)
        assertTrue(observed.scores.any{it.contributions["weather"]==.02&&it.contributions["time"]==.02})
        val claimed=s.copy(timeWeather=s.timeWeather.mapValues{(k,v)->if(k=="weather")v.copy(origin="USER_STATEMENT") else v})
        assertTrue(ranker.rank("user",p,claimed,f.now,f.epoch,null,topK=500).scores.all{it.contributions["weather"]==0.0})
        System.getenv("GLEO_INTERNAL_RESULTS")?.let{out->File(out,"night-rain-contributions.json").writeText(JSONObject()
            .put("scope","ISOLATED_SYNTHETIC_NIGHT_RAIN_NOT_ACTUAL_WEATHER")
            .put("scores",org.json.JSONArray(observed.scores.filter{it.contributions["weather"]==.02}.take(5).map{
                JSONObject().put("musicId",it.musicId).put("parts",JSONObject(it.contributions))})).toString(2))}
    }
    @Test(expected=IllegalArgumentException::class) fun differentRunIsRejected(){
        val m=f.model;ContextContributionAudit.inspect(ContextFusionRanker(m,LocalClapTextEncoder{null}),"user","other","drive",
            m.onboarding(setOf("pop"),setOf("happy")),f.snapshot(),f.now,f.epoch,null,emptyList())
    }
    @Test fun existingTasteTopTenExactlyPreservedWhenSemanticWeightIsZero(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"));val baseline=ClapRecommendationEngine(m).rank(p)
        val actual=UserExpressionEngine(m).rank("user","ENERGETIC",p,null)
        assertEquals(baseline.map{it.musicId},actual.map{it.musicId});baseline.zip(actual).forEach{assertEquals(it.first.score,it.second.total,0.0)}
    }
    @Test fun differentUsersLearnDifferentExpressionsAndDuplicatesDoNotChangeVector(){
        val m=f.model;val engine=UserExpressionEngine(m);val ids=m.tracks.map{it.musicId}
        val a=listOf(f.case("a1",ids[0]),f.case("a2",ids[1]),f.case("a3",ids[0]))
        val b=listOf(f.case("b1",ids[450],"other"),f.case("b2",ids[451],"other"),f.case("b3",ids[450],"other"))
        val proofs=(a+b).map(f::proof)
        val learned=engine.learn("user","ENERGETIC",a+b,proofs,f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST)
        val other=engine.learn("other","ENERGETIC",a+b,proofs,f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST)
        assertNotNull(learned.vector);assertNotNull(other.vector);assertFalse(learned.vector!!.contentEquals(other.vector!!))
        val duplicated=engine.learn("user","ENERGETIC",a+a+b,proofs,f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST)
        assertArrayEquals(learned.vector,duplicated.vector,0f)
        val p=m.onboarding(setOf("pop"),setOf("happy"));val before=engine.rank("user","ENERGETIC",p,null)
        val after=engine.rank("user","ENERGETIC",p,learned,.15)
        assertTrue(after.any{r->kotlin.math.abs(r.total-r.preferenceScore)>1e-5})
        val out=System.getenv("GLEO_INTERNAL_RESULTS")?.let{File(it)} ?: return
        File(out,"expression-results.json").writeText(JSONObject().put("origin","ISOLATED_SYNTHETIC_FEEDBACK_REAL_EMBEDDINGS")
            .put("before",org.json.JSONArray(before.map{JSONObject().put("id",it.musicId).put("score",it.total)}))
            .put("after",org.json.JSONArray(after.map{JSONObject().put("id",it.musicId).put("score",it.total)}))
            .put("other",org.json.JSONArray(engine.rank("other","ENERGETIC",p,other,.15).map{it.musicId})).toString(2))
    }
    @Test fun absentInvalidFutureTestOrUnboundEvidenceIsNotLearned(){
        val m=f.model;val c=f.case("one",m.tracks[0].musicId);val engine=UserExpressionEngine(m)
        assertNull(engine.learn("user","ENERGETIC",listOf(c),listOf(f.proof(c)),f.epoch).vector) // test evidence cannot enter LIVE
        assertNull(engine.learn("user","ENERGETIC",listOf(c.copy(updatedAtEpochMs=f.epoch+1)),listOf(f.proof(c)),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector)
        assertNull(engine.learn("user","ENERGETIC",listOf(c.copy(contextSnapshotId=null)),listOf(f.proof(c)),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector)
    }
    @Test fun explicitPlaybackExclusionsAndOtherUserFallbackArePreserved(){
        val m=f.model;val p=m.onboarding(setOf("pop"),setOf("happy"));val keep=m.tracks.take(2).map{it.musicId}.toSet()
        val wrong=LearnedExpression("other","ENERGETIC",m.fingerprint,m.vector(m.tracks[0].musicId),3,2,"fixture")
        val got=UserExpressionEngine(m).rank("user","ENERGETIC",p,wrong,.15,excluded=setOf(keep.first()),playable=keep)
        assertEquals(1,got.size);assertNull(got.single().semanticScore)
    }
    @Test fun trustedNegativeFeedbackReducesTargetSemanticScoreWithoutCrossUserLearning(){
        val m=f.model;val ids=m.tracks.take(3).map{it.musicId};val engine=UserExpressionEngine(m)
        val positive=listOf(f.case("p1",ids[0]),f.case("p2",ids[1]),f.case("p3",ids[2]))
        val negative=positive.dropLast(1)+positive.last().copy(reward=-.5,origin="UI_VERIFIED_LISTENING_FEEDBACK")
        fun learned(c:List<ExperienceCase>)=engine.learn("user","ENERGETIC",c,c.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST)
        val preference=m.onboarding(setOf("pop"),setOf("happy"))
        val before=engine.rank("user","ENERGETIC",preference,learned(positive),.15,topN=500).single{it.musicId==ids[2]}
        val after=engine.rank("user","ENERGETIC",preference,learned(negative),.15,topN=500).single{it.musicId==ids[2]}
        assertTrue(after.semanticScore!!<before.semanticScore!!)
        assertEquals(before.preferenceScore,after.preferenceScore,0.0)
    }
    @Test fun repetitionCapDoesNotManufactureMultiTrackEvidence(){
        val m=f.model;val cases=(1..20).map{f.case("repeated-$it",m.tracks[0].musicId)}
        val result=UserExpressionEngine(m).learn("user","ENERGETIC",cases,cases.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST)
        assertNull(result.vector);assertEquals(3,result.playbackCount);assertEquals(1,result.distinctTracks)
    }
    @Test fun onlyExactLegacyRequestsCanRecoverAnOlderUnknownParserIntent(){
        val m=f.model;val original=listOf(f.case("l1",m.tracks[0].musicId),f.case("l2",m.tracks[1].musicId),f.case("l3",m.tracks[0].musicId))
        fun cases(text:String)=original.map{it.copy(features=ObservedFeatures(mapOf("voice" to mapOf("command.musicIntent" to "UNKNOWN","command.text" to text))))}
        val engine=UserExpressionEngine(m);val known=cases("신나는 노래 틀어줘")
        assertNotNull(engine.learn("user","ENERGETIC",known,known.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector)
        val uncertain=cases("아무거나")
        assertNull(engine.learn("user","ENERGETIC",uncertain,uncertain.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector)
    }
    @Test fun cbrJournalReloadProducesSameLearnedExpression(){
        val m=f.model;val cases=listOf(f.case("r1",m.tracks[0].musicId),f.case("r2",m.tracks[1].musicId),f.case("r3",m.tracks[0].musicId))
        val file=File(Files.createTempDirectory("gleo-internal-cbr").toFile(),"cases.jsonl")
        file.writeText(cases.joinToString("\n",postfix="\n"){c->JSONObject().put("schemaVersion",1).put("caseId",c.caseId).put("localUserId",c.localUserId)
            .put("musicId",c.musicId).put("playbackSessionId",c.playbackSessionId).put("features",JSONObject(c.features.facets)).put("reward",c.reward)
            .put("feedbackEventIds",org.json.JSONArray(c.feedbackEventIds)).put("updatedAtEpochMs",c.updatedAtEpochMs).put("origin",c.origin)
            .put("contextSnapshotId",c.contextSnapshotId).put("recommendationId",c.recommendationId).put("driveSessionId",c.driveSessionId).toString()})
        val loaded=ExperienceCaseRepository(file,"user").cases();val engine=UserExpressionEngine(m)
        assertArrayEquals(engine.learn("user","ENERGETIC",cases,cases.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector,
            engine.learn("user","ENERGETIC",loaded,loaded.map(f::proof),f.epoch,ExpressionEvidenceEnvironment.ISOLATED_TEST).vector,0f)
    }
}
