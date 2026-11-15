package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.internalai.ContextContributionAudit
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Real 500 audio vectors and real precomputed text vectors. All contexts/users/playback callbacks here are SYNTHETIC. */
class Step12ContextPersonalizationTest {
 private val now=100_000L
 private val epoch=Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
 private fun asset(n:String)=File("app/src/main/assets/$n").readText()
 private class Rig(val root:File,val user:String,val adapter:RecommendationModelAdapter,val model:MusicEmbeddingRepository)
 private fun rig(user:String="fixture-A",genres:Set<String> = setOf("pop"),moods:Set<String> = setOf("happy")):Rig {
  val source=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue(source!=null)
  val root=Files.createTempDirectory("gleo-step12-").toFile();val d=File(root,"models/clap500").apply{mkdirs()}
  listOf("manifest.json","embeddings.bin").forEach{File(source!!,it).copyTo(File(d,it))}
  val runtime=OperationalRecommendationRuntime(root,user,{asset("clap-mood-prototypes-v1.json")},{asset("audio-assets/catalog-v1.json")})
  // True is a test double only; Android regression uses PlaybackMappingVerifier and actual resource.
  val a=RecommendationModelAdapter(root,user,{true},contextElapsedMs={now},contextRunId={"fixture-run"},operationalRuntime=runtime)
  assertTrue(a.load());a.initialize(genres,moods)
  val m=RecommendationModelAdapter::class.java.getDeclaredField("model").apply{isAccessible=true}.get(a) as MusicEmbeddingRepository
  return Rig(root,user,a,m)
 }
 private fun field(v:Any?,origin:String="OBSERVATION",at:Long=now)=ContextField(v,"EXPLICIT_SYNTHETIC_FIXTURE",null,at,ContextFreshness.FRESH,null,"isolated_synthetic",origin=origin)
 private fun snapshot(r:Rig,id:String,hour:Int,rain:Boolean=false,weekend:Boolean=false):ContextSnapshot {
  val cmd=VoiceCommand.testText(r.user,"fixture-run","fixture-drive",CommandIntent.RECOMMEND_AND_PLAY,"신나는 노래 틀어줘","2026-11-15T12:00:00Z",now)
  val p=ContextSnapshotProvider(r.user,"fixture-run",ContextObservationStore{"fixture-drive"},
   {d->DrivingRecommendationInput("fixture-run",d,now,1,"available",null,30.0,false,false,"moving",10000,1.0,now,environment="isolated_synthetic")},
   {now},"isolated_synthetic")
  return p.capture("fixture-drive",cmd).copy(contextSnapshotId="fixture-$id",currentLocation=mapOf("position" to field(mapOf("latitude" to 37.5,"longitude" to 127.0))),
   destination=mapOf("navigation" to field(mapOf("name" to if(weekend)"fixture-park" else if(hour<12)"fixture-office" else "fixture-home"))),
   road=mapOf("traffic" to field(mapOf("status" to if(rain)"fixture-congestion-observed" else "fixture-clear-observed"))),
   timeWeather=mapOf("deviceTime" to field(mapOf("hour" to hour,"dayOfWeek" to if(weekend)"SATURDAY" else "MONDAY")),
    "weather" to field(mapOf("condition" to if(rain)"rain" else "clear"),"API_OBSERVATION")))
 }
 private fun catalog(r:Rig)=r.model.tracks.map{Track(it.musicId,it.musicId,"MODEL_ONLY_FIXTURE_NOT_PLAYBACK_VALIDATION")}
 private fun rank(r:Rig,s:ContextSnapshot)=r.adapter.recommend(catalog(r),MusicScope.ALL,emptySet(),emptySet(),0,null,"fixture-drive",snapshot=s,utterance="신나는 노래 틀어줘").fusionDecision!!
 private fun neutral(s:ContextSnapshot)=s.copy(currentLocation=emptyMap(),destination=emptyMap(),driving=emptyMap(),road=emptyMap(),timeWeather=emptyMap(),voice=emptyMap(),
  drivingInput=s.drivingInput.copy(quality="unknown",fallbackReason="fixture_all_context_removed"))
 private fun json(v:Any?):Any?=when(v){null,JSONObject.NULL->null;is JSONObject->v.keys().asSequence().associateWith{json(v.get(it))};is JSONArray->(0 until v.length()).map{json(v.get(it))};else->v}
 private fun retain(r:Rig,s:ContextSnapshot,music:String,kind:FeedbackKind):Pair<PipelineLedger,String> {
  val l=PipelineLedger(File(r.root,"fixture-ledger-${s.contextSnapshotId}"))
  val voice=s.voiceRequestId!!;val ids=CausalIds(r.user,"fixture-run","fixture-drive",voice,s.contextSnapshotId,"rec-${s.contextSnapshotId}")
  l.append(LedgerKind.VOICE_REQUEST,ids.copy(contextSnapshotId=null,recommendationId=null),mapOf("source" to "TEST_TEXT","intent" to "RECOMMEND_AND_PLAY"),voice)
  l.append(LedgerKind.CONTEXT_SNAPSHOT,ids.copy(recommendationId=null),s.toMap(),s.contextSnapshotId)
  l.append(LedgerKind.RECOMMENDATION_REQUEST,ids,mapOf("playableMusicIds" to listOf(music)))
  val run=r.adapter.recommend(listOf(Track(music,music,"fixture")),MusicScope.ALL,emptySet(),emptySet(),0,null,"fixture-drive",snapshot=s,utterance="신나는 노래 틀어줘")
  assertEquals(listOf(music),run.musicIds)
  l.append(LedgerKind.RECOMMENDATION_RESULT,ids,mapOf("origin" to "AI_MODEL","musicIds" to run.musicIds,"frozenEvidence" to json(JSONObject(run.contextEvidence!!))))
  val provider=ContextSnapshotProvider(r.user,"fixture-run",ContextObservationStore{"fixture-drive"},{s.drivingInput},{now},"isolated_synthetic")
  val p=ContextPipeline(r.user,"fixture-run",{"fixture-drive"},provider,l,{now})
  val t=p.preparePlayback(ids.recommendationId!!,music);val pb="synthetic-pb-${s.contextSnapshotId}"
  p.bindObservedPlayback(t.playbackRequestId,music,pb,"fixture-drive") // explicit callback DOUBLE
  val c=VoiceCommand.testText(r.user,"fixture-run","fixture-drive",if(kind==FeedbackKind.LIKE)CommandIntent.LIKE else CommandIntent.DISLIKE,"fixture explicit feedback","2026-11-15T12:00:00Z",now)
  p.commandExecuted(c,pb,music,"SUCCESS");val event=p.feedback(pb,music,kind,c.voiceRequestId)
  assertTrue(r.adapter.retainExplicitExperience(ExperienceCaseRepository.forUser(r.root,r.user),l,event,s))
  assertFalse(r.adapter.retainExplicitExperience(ExperienceCaseRepository.forUser(r.root,r.user),l,event,s))
  return l to event
 }
 @Test fun actualOperatingAdapterExportsAll500ScoresForControlledScenariosAtoF() {
  val r=rig();val a=snapshot(r,"A",8);val b=snapshot(r,"B",18,true);val c=snapshot(r,"C",14,weekend=true)
  val target=rank(r,a).scores[7].musicId
  val before=rank(r,a);retain(r,a,target,FeedbackKind.LIKE);val after=rank(r,a)
  assertTrue(after.scores.single{it.musicId==target}.cbr>before.scores.single{it.musicId==target}.cbr)
  // Independent, explicitly labelled synthetic history for each controlled situation.
  // Candidate rank positions select test inputs, not human relevance labels or tuning targets.
  retain(r,b,rank(r,b).scores.filter{it.musicId!=target}[8].musicId,FeedbackKind.LIKE)
  retain(r,c,rank(r,c).scores.filter{it.cbr==0.0}[9].musicId,FeedbackKind.LIKE)
  val cases=ExperienceCaseRepository.forUser(r.root,r.user).cases();assertEquals(3,cases.size)
  val e=a.copy(contextSnapshotId="fixture-E",currentLocation=emptyMap(),destination=emptyMap(),road=emptyMap())
  val missingAll=rank(r,neutral(a))
  assertTrue(missingAll.observed.facets.values.all{it.isEmpty()})
  assertTrue(missingAll.scores.all{it.cbr==0.0})
  fun expired(f:Map<String,ContextField>,at:Long)=f.mapValues{it.value.copy(observedElapsedMs=at)}
  val f=a.copy(contextSnapshotId="fixture-F",currentLocation=expired(a.currentLocation,now+1),destination=expired(a.destination,now-15001),
   driving=expired(a.driving,now-2501),road=expired(a.road,now+1),timeWeather=expired(a.timeWeather,now-600001),voice=expired(a.voice,now+1),
   drivingInput=a.drivingInput.copy(latestObservedElapsedMs=now+1))
  val variants=listOf("A" to a,"B" to b,"C" to c,"E" to e,"F" to f)
  val reports=variants.map{(name,s)->
   val full=rank(r,s);val baseline=rank(r,neutral(s))
   assertEquals(500,full.scores.size)
   full.scores.forEach{row->assertEquals(row.total,row.contributions.values.sum(),1e-12);assertEquals(row.contributions.getValue("cbr"),row.contextCbrContributions.values.sum(),1e-12)}
   if(name=="F"){assertTrue(full.observed.facets.values.all{it.isEmpty()});assertFalse(full.legacySpeed.applied);assertTrue(full.scores.all{it.cbr==0.0});assertEquals(baseline.scores,full.scores)}
   val baseRanks=baseline.scores.mapIndexed{i,row->row.musicId to i+1}.toMap()
   val audit=ContextContributionAudit.inspect(ContextFusionRanker(r.model,CuratedMusicTextEncoder(asset("clap-mood-prototypes-v1.json"))),r.user,"fixture-run","fixture-drive",r.model.onboarding(setOf("pop"),setOf("happy")),s,now,System.currentTimeMillis(),if(name=="F")null else "신나는 노래 틀어줘",cases,topN=10)
   JSONObject().put("scenario",name).put("input",JSONObject(s.toMap())).put("decision",JSONObject(full.toMap()))
    .put("baseline",JSONArray(baseline.scores.map{JSONObject().put("musicId",it.musicId).put("score",it.total).put("rank",baseRanks[it.musicId])}))
    .put("changedRanks",full.scores.mapIndexed{i,row->if(baseRanks[row.musicId]!=i+1)1 else 0}.sum())
    .put("ablationTop10",JSONArray(audit.map{JSONObject().put("musicId",it.musicId).put("removals",JSONArray(it.removals.map{d->JSONObject().put("facet",d.facet).put("scoreDelta",d.scoreDelta).put("rankWithout",d.rankWithout)}))}))
  }
  val other=rig("fixture-B",setOf("ambient"),setOf("peaceful"));val d=rank(other,a.copy(localUserId=other.user))
  assertNotEquals(after.scores.map{it.musicId},d.scores.map{it.musicId});assertTrue(d.retrievedCaseIds.isEmpty())
  val out=JSONObject().put("dataSource","REAL_500_VECTORS_SYNTHETIC_CONTEXTS_USERS_CALLBACKS").put("scenarios",JSONArray(reports))
   .put("beforeFeedback",JSONObject(before.toMap())).put("afterFeedback",JSONObject(after.toMap())).put("target",target)
   .put("missingAll",JSONObject(missingAll.toMap())).put("D_otherUser",JSONObject(d.toMap())).put("modelVersion",r.model.modelVersion).put("scoreCount",500)
  System.getenv("GLEO_STEP12_RESULTS")?.let{File(it).writeText(out.toString(2))}
 }
 @Test fun everyFacetHasExactConditionalCbrContributionAndNoMissingBonus() {
  val r=rig();val s=snapshot(r,"six",8);val features=ContextFeatureExtractor.extract(s,now)
  assertTrue(ContextContributionEvidence.facets.all{features.facets.getValue(it).isNotEmpty()})
  val id=r.model.tracks.first().musicId
  val sample=ExperienceCase("fixture-case",r.user,id,"fixture-pb",features,1.0,setOf("fixture-feedback"),epoch,"EXPLICIT_TEST_FIXTURE")
  val ranker=ContextFusionRanker(r.model,LocalClapTextEncoder{null})
  val full=ranker.rank(r.user,r.model.vector(id)!!,s,now,epoch,null,listOf(sample),topK=500)
  val row=full.scores.single{it.musicId==id}
  assertEquals(.15,row.contextCbrContributions.values.sum(),1e-12)
  row.contextCbrContributions.values.forEach{assertEquals(.025,it,1e-12)}
  val removed=ranker.rank(r.user,r.model.vector(id)!!,s.copy(destination=emptyMap()),now,epoch,null,listOf(sample),topK=500)
  assertEquals(0.0,removed.scores.single{it.musicId==id}.contextCbrContributions.getValue("destination"),0.0)
  assertEquals(.025,row.total-removed.scores.single{it.musicId==id}.total,1e-12)
 }
 @Test fun verifiedAliasRetainsCanonicalCaseAndDuplicateFeedbackDoesNotLearnAgain() {
  val r=rig();val s=snapshot(r,"alias",8)
  val (ledger,id)=retain(r,s,"jamendo_0010",FeedbackKind.LIKE)
  assertEquals("jamendo_api_1559614",ExperienceCaseRepository.forUser(r.root,r.user).cases().single().musicId)
  val reopened=ExperienceCaseRepository.forUser(r.root,r.user)
  assertFalse(r.adapter.retainExplicitExperience(reopened,ledger,id,s))
  assertEquals(1,reopened.cases().size)
  assertEquals(ProfileFeedbackOutcome.APPLIED_FIXED_RULE,r.adapter.applyExplicitProfileFeedback(ledger,id))
  assertEquals(ProfileFeedbackOutcome.ALREADY_APPLIED,r.adapter.applyExplicitProfileFeedback(ledger,id))
 }
 @Test fun unsupportedAliasIsNotGuessedOrLearned() {
  val r=rig();val s=snapshot(r,"unverified",8)
  val run=r.adapter.recommend(listOf(Track("jamendo_0016","fixture","fixture")),MusicScope.ALL,emptySet(),emptySet(),0,null,"fixture-drive",snapshot=s)
  assertEquals(RecommendationOrigin.TEST_CATALOG,run.origin)
  assertTrue(ExperienceCaseRepository.forUser(r.root,r.user).cases().isEmpty())
 }
 @Test fun explicitDislikeIsNegativeButDoesNotCrossUsers() {
  val r=rig();val s=snapshot(r,"negative",8);val id=rank(r,s).scores.first().musicId
  retain(r,s,id,FeedbackKind.DISLIKE)
  assertTrue(rank(r,s).scores.single{it.musicId==id}.cbr<0)
  val cases=ExperienceCaseRepository.forUser(r.root,r.user).cases()
  assertTrue(CaseBasedReasoner.retrieve("other",ContextFeatureExtractor.extract(s,now),cases,System.currentTimeMillis()).isEmpty())
 }
 @Test fun negativeFutureExpiredAndClaimedClocksNeverBecomeFeatures() {
  val r=rig();val s=snapshot(r,"clock",8)
  for(at in listOf(-1L,now+1,now-600001)) {
   val invalid=s.copy(currentLocation=s.currentLocation.mapValues{it.value.copy(observedElapsedMs=at)},destination=emptyMap(),driving=emptyMap(),road=emptyMap(),timeWeather=emptyMap(),voice=emptyMap())
   assertTrue(ContextFeatureExtractor.extract(invalid,now).facets.values.all{it.isEmpty()})
  }
  val claimed=s.copy(timeWeather=mapOf("weather" to field(mapOf("condition" to "rain"),"USER_STATEMENT")))
  assertFalse(ContextFeatureExtractor.extract(claimed,now).facets.getValue("timeWeather").keys.any{it.startsWith("weather")})
 }
}
