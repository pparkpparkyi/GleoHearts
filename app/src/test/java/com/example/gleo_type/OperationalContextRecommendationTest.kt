package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

/** Real model/prototypes, synthetic contexts and explicit callback doubles. No actual commute/weather. */
class OperationalContextRecommendationTest {
 private fun asset(name:String):String=listOf(File("src/main/assets/$name"),File("app/src/main/assets/$name")).first{it.isFile}.readText()
 private class Rig(val root:File,val user:String="synthetic-user") {
  var now=10000L
  val observations=ContextObservationStore{"drive"}
  val snapshots=ContextSnapshotProvider(user,"run",observations,{d->DrivingRecommendationInput("run",d,now,null,"unknown","not_observed",environment="isolated_synthetic")},
   {now},"isolated_synthetic",{Instant.parse("2026-11-13T08:00:00Z")},{ZoneId.of("UTC")})
 }
 private fun setup():Pair<Rig,RecommendationModelAdapter> {
  val source=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue(source!=null)
  val r=Rig(Files.createTempDirectory("gleo-context-test").toFile())
  val dest=File(r.root,"models/clap500").apply{mkdirs()}
  listOf("manifest.json","embeddings.bin").forEach{File(source!!,it).copyTo(File(dest,it))}
  val runtime=OperationalRecommendationRuntime(r.root,r.user,{asset("clap-mood-prototypes-v1.json")},{asset("audio-assets/catalog-v1.json")})
  val model=RecommendationModelAdapter(r.root,r.user,contextSnapshotProvider={r.snapshots.capture(it)},contextElapsedMs={r.now},contextRunId={"run"},operationalRuntime=runtime)
  assertTrue(model.load());model.initialize(setOf("pop"),setOf("happy"));return r to model
 }
 private fun catalog(model:RecommendationModelAdapter)=model.modelTrackIds().map{Track(it,it,"synthetic metadata")}
 @Test fun automaticRuntimeUsesFrozenSnapshotAndPersistsEvidenceWithoutCurrentReads() {
  val (r,m)=setup();val run=m.recommend(catalog(m),MusicScope.THREE,emptySet(),emptySet(),0,null,"drive")
  assertEquals(RecommendationOrigin.AI_MODEL,run.origin);assertNotNull(run.fusionDecision);assertNotNull(run.contextEvidence)
  val frozen=run.contextEvidence!!;val j=JSONObject(frozen)
  assertEquals("NO_UTTERANCE",j.getJSONObject("decision").getString("textStatus"))
  assertEquals("DIAGNOSTIC_ONLY_NOT_RANKING",j.getJSONObject("clusters").getString("usage"))
  r.now+=120000;val history=LocalRecommendationHistory(File(r.root,"history"));history.append(run)
  assertEquals(frozen,history.load().single().contextEvidence)
  assertEquals(j.getJSONObject("snapshot").getString("contextSnapshotId"),JSONObject(history.load().single().contextEvidence!!).getJSONObject("snapshot").getString("contextSnapshotId"))
 }
 @Test fun explicitCausalLikeFeedsOperationalCbrWithoutInventingCases() {
  val (r,m)=setup();val ledger=PipelineLedger(File(r.root,"ledger"));val p=ContextPipeline(r.user,"run",{"drive"},r.snapshots,ledger,{r.now})
  val command=VoiceCommand.testText(r.user,"run","drive",CommandIntent.RECOMMEND_AND_PLAY,"신나는 노래 틀어줘","2026-11-13T08:00:00Z",r.now)
  val plan=p.recommend(command,catalog(m),m)
  assertEquals("REAL_CLAP_PRECOMPUTED_CURATED_ENGLISH",plan.run.fusionDecision!!.textStatus)
  assertTrue(plan.run.fusionDecision!!.retrievedCaseIds.isEmpty())
  val id=plan.run.musicIds.first();val ticket=p.preparePlayback(plan.recommendationId,id)
  p.bindObservedPlayback(ticket.playbackRequestId,id,"synthetic-pb","drive")
  val like=VoiceCommand.testText(r.user,"run","drive",CommandIntent.LIKE,"이 노래 좋아","2026-11-13T08:00:00Z",r.now)
  p.commandExecuted(like,"synthetic-pb",id,"SUCCESS");val fid=p.feedback("synthetic-pb",id,FeedbackKind.LIKE,like.voiceRequestId)
  val cases=ExperienceCaseRepository.forUser(r.root,r.user)
  assertTrue(m.retainExplicitExperience(cases,ledger,fid,plan.snapshot));assertFalse(m.retainExplicitExperience(cases,ledger,fid,plan.snapshot))
  val next=m.recommend(catalog(m),MusicScope.ALL,emptySet(),emptySet(),0,null,"drive",snapshot=plan.snapshot,utterance=command.text)
  assertTrue(next.fusionDecision!!.retrievedCaseIds.isNotEmpty());assertTrue(next.fusionDecision!!.scores.single{it.musicId==id}.cbr>0)
  assertEquals(ProfileFeedbackOutcome.APPLIED_FIXED_RULE,m.applyExplicitProfileFeedback(ledger,fid))
  assertEquals(ProfileFeedbackOutcome.ALREADY_APPLIED,m.applyExplicitProfileFeedback(ledger,fid))
 }
 @Test fun syntheticRainAddsOnlyObservedWeatherAndUnsupportedRoadHasNoDirectBonus() {
  val (r,m)=setup();val base=r.snapshots.capture("drive")
  fun field(v:Any?,origin:String="OBSERVATION")=ContextField(v,"ISOLATED_SYNTHETIC",null,r.now,ContextFreshness.FRESH,null,"isolated_synthetic",origin=origin)
  val clear=base.copy(destination=mapOf("navigation" to field(mapOf("name" to "synthetic office"))),road=mapOf("traffic" to field(mapOf("status" to "clear"))))
  val rain=clear.copy(timeWeather=clear.timeWeather+("weather" to field(mapOf("condition" to "rain"),"API_OBSERVATION")),road=mapOf("traffic" to field(mapOf("status" to "congested"))))
  fun rank(s:ContextSnapshot)=m.recommend(catalog(m),MusicScope.ALL,emptySet(),emptySet(),0,null,"drive",snapshot=s).fusionDecision!!
  val a=rank(clear);val b=rank(rain);assertTrue(a.scores.all{it.contributions.getValue("weather")==0.0})
  assertEquals(rank(base).scores,a.scores) // no invented commute/office/road bonus without matched cases
  assertTrue(b.scores.any{it.contributions.getValue("weather")>0});assertTrue(b.scores.all{it.contributions.getValue("roadDirect")==0.0&&it.contributions.getValue("destinationDirect")==0.0})
  assertTrue(rank(rain.copy(timeWeather=rain.timeWeather+("weather" to field(mapOf("condition" to "rain"),"USER_STATEMENT")))).scores.all{it.contributions.getValue("weather")==0.0})
  assertEquals(rank(clear).scores,rank(clear).scores)
 }
 @Test fun missingModelAndUnattributedUtteranceUseHonestFallbacks() {
  val root=Files.createTempDirectory("gleo-no-model").toFile();val m=RecommendationModelAdapter(root,"user")
  val fallback=m.recommend(listOf(Track("music_001","local","test")),MusicScope.THREE,emptySet(),emptySet(),0,null,null)
  assertEquals(RecommendationOrigin.TEST_CATALOG,fallback.origin);assertNull(fallback.contextEvidence)
  val (r,real)=setup();val noVoice=real.recommend(catalog(real),MusicScope.THREE,emptySet(),emptySet(),0,null,"drive",snapshot=r.snapshots.capture("drive"),utterance="신나는 노래 틀어줘")
  assertEquals("NO_UTTERANCE",noVoice.fusionDecision!!.textStatus)
 }
 @Test fun sameCuratedUtteranceStillDistinguishesTwoSyntheticPreferences() {
  val (r,m)=setup();val dir=File(r.root,"models/clap500");val j=JSONObject(File(dir,"manifest.json").readText());val rows=j.getJSONArray("tracks")
  val tracks=(0 until rows.length()).map{rows.getJSONObject(it).let{x->EmbeddingTrack(x.getString("musicId"),x.getString("genres").split('|').toSet(),x.getString("moods").split('|').toSet())}}
  val model=MusicEmbeddingRepository.load(File(dir,"embeddings.bin").readBytes(),tracks,j.getString("modelVersion"),j.getString("sha256"))
  val ranker=ContextFusionRanker(model,CuratedMusicTextEncoder(asset("clap-mood-prototypes-v1.json")))
  val a=ranker.rank(r.user,model.vector(tracks.first().musicId)!!,r.snapshots.capture("drive"),r.now,0,"신나는 노래 틀어줘",topK=500)
  val b=ranker.rank("synthetic-other",model.vector(tracks.last().musicId)!!,r.snapshots.capture("drive").copy(localUserId="synthetic-other"),r.now,0,"신나는 노래 틀어줘",topK=500)
  assertEquals(a.textModel,b.textModel);assertNotEquals(a.scores.map{it.musicId},b.scores.map{it.musicId})
 }
}
