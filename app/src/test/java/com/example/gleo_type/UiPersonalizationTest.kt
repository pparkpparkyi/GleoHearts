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

class UiPersonalizationTest {
 private fun asset(s:String)=listOf(File("src/main/assets/$s"),File("app/src/main/assets/$s")).first{it.isFile}.readText()
 private class Rig(val root:File,val user:String="ui-synthetic-user") {
  var now=10000L;var drive:String?="drive"
  val snapshots=ContextSnapshotProvider(user,"run",ContextObservationStore{drive},{d->DrivingRecommendationInput("run",d,now,null,"unknown","not_observed",environment="isolated_synthetic")},
   {now},"isolated_synthetic",{Instant.parse("2026-11-14T08:00:00Z")},{ZoneId.of("UTC")})
  val ledger=PipelineLedger(File(root,"ai-pipeline/v1/ledger.jsonl"))
  val pipeline=ContextPipeline(user,"run",{drive},snapshots,ledger,{now})
  val ui=UiPersonalization(user,"run",{drive},pipeline,{now})
 }
 private fun setup():Pair<Rig,RecommendationModelAdapter> {
  val src=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue(src!=null)
  val r=Rig(Files.createTempDirectory("ui-learning").toFile());val dest=File(r.root,"models/clap500").apply{mkdirs()}
  listOf("manifest.json","embeddings.bin").forEach{File(src!!,it).copyTo(File(dest,it))}
  return r to adapter(r).also{assertTrue(it.load());it.initialize(setOf("pop"),setOf("happy"))}
 }
 private fun adapter(r:Rig)=RecommendationModelAdapter(r.root,r.user,contextSnapshotProvider={r.snapshots.capture(it)},contextElapsedMs={r.now},contextRunId={"run"},
  operationalRuntime=OperationalRecommendationRuntime(r.root,r.user,{asset("clap-mood-prototypes-v1.json")},{asset("audio-assets/catalog-v1.json")}))
 private fun rank(r:Rig,m:RecommendationModelAdapter)=m.recommend(m.modelTrackIds().map{Track(it,it,"synthetic")},MusicScope.ALL,emptySet(),emptySet(),0,null,r.drive)
 private fun bind(r:Rig,run:RecommendationRun,id:String,pb:String){val ticket=r.ui.prepare(run,id)!!;r.pipeline.bindObservedPlayback(ticket.playbackRequestId,id,pb,r.drive)}
 @Test fun actualBindingRequiredLikeChangesOperationalScoresAndDuplicateAndRestartAreStable() {
  val (r,m)=setup();val before=rank(r,m);val id=before.musicIds[10]
  assertFalse(r.ui.like(id,"unobserved",r.drive,m));assertTrue(ExperienceCaseRepository.forUser(r.root,r.user).cases().isEmpty())
  bind(r,before,id,"observed-test-double")
  assertTrue(r.ui.like(id,"observed-test-double",r.drive,m));val after=rank(r,m)
  assertTrue(after.scores.getValue(id)>before.scores.getValue(id));assertTrue(after.musicIds.indexOf(id)<before.musicIds.indexOf(id))
  val cases=ExperienceCaseRepository.forUser(r.root,r.user);val saved=cases.cases()
  assertTrue(r.ui.like(id,"observed-test-double",r.drive,m));assertEquals(saved,cases.cases());assertEquals(after.scores,rank(r,m).scores)
  val reopened=adapter(r);assertEquals(after.scores,rank(r,reopened).scores)
  val evidence=JSONObject().put("synthetic",true).put("musicId",id).put("beforeScore",before.scores[id]).put("afterScore",after.scores[id])
   .put("beforeRank",before.musicIds.indexOf(id)+1).put("afterRank",after.musicIds.indexOf(id)+1)
  System.getenv("GLEO_STEP8C_PROOF")?.let{File(it).writeText(evidence.toString(2))}
 }
 @Test fun weakSkipUsesExistingPolicyOnceAndExplicitLikeTakesPriority() {
  val (r,m)=setup();val before=rank(r,m);val id=before.musicIds.first();bind(r,before,id,"pb")
  val input=PlaybackFeedback("run/drive/pb",id,.05,1000.0,true,naturalCompletion=false,confirmedManualSkip=true,seekObserved=false)
  UiPersonalization.summary(r.root,r.user,input,FixedFeedbackPolicy.reward(input),m)
  val after=rank(r,m);assertTrue(after.scores.getValue(id)<before.scores.getValue(id))
  val cases=ExperienceCaseRepository.forUser(r.root,r.user);val original=cases.cases()
  UiPersonalization.summary(r.root,r.user,input,FixedFeedbackPolicy.reward(input),m);assertEquals(original,cases.cases())
  assertTrue(r.ui.like(id,"pb",r.drive,m));assertEquals(1.0,cases.cases().single().reward,0.0)
  UiPersonalization.summary(r.root,r.user,input,FixedFeedbackPolicy.reward(input),m);assertEquals(1.0,cases.cases().single().reward,0.0)
  assertNull(FixedFeedbackPolicy.reward(input.copy(seekObserved=true)))
  assertNull(FixedFeedbackPolicy.reward(input.copy(trustworthy=false)))
 }
 @Test fun staleScopeWrongUserAndUnattributedSelectionNeverTrain() {
  val (r,m)=setup();val run=rank(r,m);r.now+=30001
  assertNull(r.ui.prepare(run,run.musicIds.first()));r.now=10000;r.drive="other"
  assertNull(r.ui.prepare(run,run.musicIds.first()));r.drive="drive"
  val other=UiPersonalization("other-user","run",{r.drive},r.pipeline,{r.now})
  assertTrue(runCatching{other.prepare(run,run.musicIds.first())}.isFailure)
  assertTrue(ExperienceCaseRepository.forUser(r.root,r.user).cases().isEmpty())
 }
 @Test fun sameUtteranceAndOnboardingRemainSeparatedByUserAndRepeatedCasesAreBounded() {
  val (r,m)=setup();val other=Rig(r.root,"second-synthetic-user");val b=adapter(other)
  assertTrue(b.load());b.initialize(setOf("pop"),setOf("happy"))
  fun requested(rig:Rig,model:RecommendationModelAdapter):RecommendationRun {
   val command=VoiceCommand.testText(rig.user,"run",rig.drive,CommandIntent.RECOMMEND_AND_PLAY,"신나는 노래 틀어줘","2026-11-14T08:00:00Z",rig.now)
   return model.recommend(model.modelTrackIds().map{Track(it,it,"synthetic")},MusicScope.ALL,emptySet(),emptySet(),0,null,rig.drive,
    snapshot=rig.snapshots.capture(rig.drive,command),utterance=command.text)
  }
  val before=requested(r,m);val target=before.musicIds[10]
  repeat(5){i->val run=requested(r,m);bind(r,run,target,"repeat-$i");assertTrue(r.ui.like(target,"repeat-$i",r.drive,m))}
  val a=requested(r,m);val bb=requested(other,b)
  assertTrue(a.fusionDecision!!.scores.single{it.musicId==target}.cbr>0)
  assertEquals(0.0,bb.fusionDecision!!.scores.single{it.musicId==target}.cbr,0.0)
  assertNotEquals(a.musicIds,bb.musicIds)
  val rows=ExperienceCaseRepository.forUser(r.root,r.user).cases();assertEquals(5,rows.size)
  val runtime=OperationalRecommendationRuntime(r.root,r.user,{asset("clap-mood-prototypes-v1.json")},{asset("audio-assets/catalog-v1.json")})
  assertEquals(3,runtime.boundedUiCases(rows).size)
 }
 @Test fun repeatedVerifiedEarlySkipsLowerRankingWithoutAdditivePenalty() {
  val (r,m)=setup();val before=rank(r,m);val target=before.musicIds[10]
  repeat(3){i->
   bind(r,before,target,"skip-$i")
   val f=PlaybackFeedback("run/drive/skip-$i",target,.05,1000.0,true,naturalCompletion=false,confirmedManualSkip=true,seekObserved=false)
   UiPersonalization.summary(r.root,r.user,f,FixedFeedbackPolicy.reward(f),m)
  }
  val after=rank(r,m);assertTrue(after.musicIds.indexOf(target)>before.musicIds.indexOf(target))
  val cbr=after.fusionDecision!!.scores.single{it.musicId==target}.cbr
  assertEquals(-.5/6,cbr,1e-9) // Only the observed time facet matches; three skips do not triple the penalty.
  System.getenv("GLEO_STEP8C_PROOF")?.let{path->File(File(path).parentFile,"jvm-skip-proof.json").writeText(JSONObject()
   .put("synthetic",true).put("musicId",target).put("beforeScore",before.scores[target]).put("afterScore",after.scores[target])
   .put("beforeRank",before.musicIds.indexOf(target)+1).put("afterRank",after.musicIds.indexOf(target)+1).put("cbr",cbr).toString(2))}
 }
}
