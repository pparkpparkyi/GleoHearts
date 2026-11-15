package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.music.JamendoResearchCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only. No model/audio upload, onboarding or production playback initiated here. */
class OperationalModelInstallDeviceTest {
 @Test fun explicitlyApprovedTwoFileInstallAndReadOnlyBenchmark() {
  val args=InstrumentationRegistry.getArguments()
  assumeTrue("Explicit model copy approval required",args.getString("gleo.approvedStep8d")=="YES")
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val stage=File(args.getString("gleo.modelStage")!!).canonicalFile
  require(stage.toPath().startsWith(c.cacheDir.canonicalFile.toPath())&&stage.name.startsWith("step8d-install-"))
  val approved=ApprovedModel(args.getString("gleo.modelVersion")!!,args.getString("gleo.manifestSize")!!.toInt(),args.getString("gleo.manifestSha256")!!,
   args.getString("gleo.embeddingSize")!!.toInt(),args.getString("gleo.embeddingSha256")!!)
  val started=android.os.SystemClock.elapsedRealtimeNanos()
  val result=OperationalModelInstaller(c.filesDir).install(approved,{File(stage,"manifest.json").inputStream()},{File(stage,"embeddings.bin").inputStream()})
  assertNotEquals(ModelInstallResult.EXISTING_MODEL_CONFLICT,result)
  val installMs=(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6
  val beforePss=android.os.Debug.getPss();val loadStart=android.os.SystemClock.elapsedRealtimeNanos()
  val adapter=RecommendationModelAdapter(c.filesDir,"READ_ONLY_STEP8D_VALIDATION",PlaybackMappingVerifier(c)::verify)
  assertTrue(adapter.load());assertEquals(500,adapter.modelTrackIds().size)
  val loadMs=(android.os.SystemClock.elapsedRealtimeNanos()-loadStart)/1e6
  val afterPss=android.os.Debug.getPss()
  val installed=File(c.filesDir,"models/clap500");val j=JSONObject(File(installed,"manifest.json").readText())
  val tracks=j.getJSONArray("tracks");val metadata=(0 until tracks.length()).map{tracks.getJSONObject(it).let{t->
   EmbeddingTrack(t.getString("musicId"),t.getString("genres").split('|').toSet(),t.getString("moods").split('|').toSet())}}
  val model=MusicEmbeddingRepository.load(File(installed,"embeddings.bin").readBytes(),metadata,approved.modelVersion,approved.embeddingSha256)
  val preference=model.onboarding(setOf("pop"),setOf("happy")) // Unsaved synthetic benchmark only.
  val times=(0 until 20).map{val start=android.os.SystemClock.elapsedRealtimeNanos()
   assertEquals(500,ClapRecommendationEngine(model).rank(preference,500).size)
   (android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6}.sorted()
  val playable=adapter.availability(JamendoResearchCatalog.load(c).map{it.musicId}.toSet())
  File(c.cacheDir,"step8d-model-install-proof.json").writeText(JSONObject().put("result",result.name).put("rows",500).put("dimensions",512)
   .put("modelVersion",approved.modelVersion).put("storageBytes",approved.manifestSize+approved.embeddingSize)
   .put("installMs",installMs).put("loadMs",loadMs).put("processPssBeforeKb",beforePss).put("processPssAfterKb",afterPss)
   .put("score500MedianMs",times[10]).put("score500MaxMs",times.last()).put("verifiedLegacyPlayableCount",playable.playableCount)
   .put("profileInitialized",false).put("audioTransferred",false).put("benchmarkInput","UNSAVED_SYNTHETIC_POP_HAPPY_NOT_PERSONAL_PROFILE").toString(2))
 }
}
