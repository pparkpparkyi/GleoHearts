package com.example.gleo_type

import com.example.gleo_type.recommendation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OperationalModelInstallTest {
 private fun input():Triple<ByteArray,ByteArray,ApprovedModel> {
  val path=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue(path!=null)
  val m=File(path!!,"manifest.json").readBytes();val b=File(path,"embeddings.bin").readBytes();val j=JSONObject(m.toString(Charsets.UTF_8))
  return Triple(m,b,ApprovedModel(j.getString("modelVersion"),m.size,OperationalModelInstaller.sha(m),b.size,OperationalModelInstaller.sha(b)))
 }
 @Test fun pinnedRealModelInstallsOnceAndLoadsWithoutUserProfileOrAudio() {
  val(m,b,a)=input();val root=Files.createTempDirectory("operational-install").toFile();val i=OperationalModelInstaller(root)
  assertEquals(ModelInstallResult.INSTALLED,i.install(a,{m.inputStream()},{b.inputStream()}))
  val model=RecommendationModelAdapter(root,"isolated-validation");assertTrue(model.load());assertEquals(500,model.modelTrackIds().size)
  assertFalse(File(root,"recommendation-model").exists());assertFalse(root.walkTopDown().any{it.extension=="mp3"})
  assertEquals(ModelInstallResult.ALREADY_MATCHING,i.install(a,{m.inputStream()},{b.inputStream()}))
  assertEquals(setOf("manifest.json","embeddings.bin"),File(root,"models/clap500").list()!!.toSet())
 }
 @Test fun badHashDimensionAndExistingModelNeverGetOverwritten() {
  val(m,b,a)=input();val root=Files.createTempDirectory("bad-install").toFile();val i=OperationalModelInstaller(root)
  assertTrue(runCatching{i.install(a,{m.inputStream()},{(b+byteArrayOf(1)).inputStream()})}.isFailure)
  assertFalse(File(root,"models/clap500").exists())
  val bad=JSONObject(m.toString(Charsets.UTF_8)).put("dimensions",256).toString().toByteArray()
  assertTrue(runCatching{i.install(a.copy(manifestSize=bad.size,manifestSha256=OperationalModelInstaller.sha(bad)),{bad.inputStream()},{b.inputStream()})}.isFailure)
  val original=File(root,"models/clap500").apply{mkdirs()};val marker=File(original,"existing-user-owned.txt").apply{writeText("keep")}
  assertEquals(ModelInstallResult.EXISTING_MODEL_CONFLICT,i.install(a,{m.inputStream()},{b.inputStream()}));assertEquals("keep",marker.readText())
 }
 @Test fun readOnlyPcLoadAndScoreBenchmarkHasNoPlaybackDependency() {
  val(m,b,a)=input();val j=JSONObject(m.toString(Charsets.UTF_8));val rows=j.getJSONArray("tracks")
  val metadata=(0 until rows.length()).map{rows.getJSONObject(it).let{t->EmbeddingTrack(t.getString("musicId"),t.getString("genres").split('|').toSet(),t.getString("moods").split('|').toSet())}}
  val runtime=Runtime.getRuntime();val before=runtime.totalMemory()-runtime.freeMemory();val start=System.nanoTime()
  val model=MusicEmbeddingRepository.load(b,metadata,a.modelVersion,a.embeddingSha256)
  val loadMs=(System.nanoTime()-start)/1e6;val delta=runtime.totalMemory()-runtime.freeMemory()-before
  val preference=model.onboarding(setOf("pop"),setOf("happy"));val engine=ClapRecommendationEngine(model)
  repeat(3){engine.rank(preference,500)}
  val times=(0 until 20).map{val t=System.nanoTime();val result=engine.rank(preference,500)
   assertEquals(500,result.size);assertTrue(result.all{it.score.isFinite()});(System.nanoTime()-t)/1e6}.sorted()
  System.getenv("GLEO_STEP8D_PROOF")?.let{File(it).writeText(JSONObject().put("environment","PC_JVM_NOT_ANDROID")
   .put("loadMs",loadMs).put("score500MedianMs",times[10]).put("score500MaxMs",times.last())
   .put("observedJvmHeapDeltaBytes",delta).put("float32MatrixPayloadBytes",500*512*4).put("modelBytes",m.size+b.size)
   .put("input","UNSAVED_SYNTHETIC_POP_HAPPY").toString(2))}
 }
}
