package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exact observed isolated Media3 JSONL rows; no synthetic duration/position/completion labels. */
class Step8cSummaryFeedbackTest {
 @Test fun actualStoppedSummaryUpdatesPendingLikePreferenceExactlyOnce() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val proofFile=File(c.cacheDir,"step8c-learning-proof.json");val proof=JSONObject(proofFile.readText())
  val root=File(proof.getString("fixtureRoot")).canonicalFile;require(root.toPath().startsWith(c.cacheDir.canonicalFile.toPath()))
  val rows=File(c.cacheDir,"audio-registry-approved-samples/journal/events").listFiles().orEmpty().filter{it.extension=="jsonl"}
   .flatMap{it.readLines()}.filter{it.isNotBlank()&&JSONObject(it).optString("runId")==proof.getString("runId")}
  assertTrue(rows.map(::JSONObject).any{it.optString("kind")=="listening_summary" && it.optString("playbackSessionId")==proof.getString("playback")})
  val destination=File(root,"events/observed-media3-only.jsonl");destination.parentFile!!.mkdirs()
  check(!destination.exists());destination.writeText(rows.joinToString("\n",postfix="\n"))
  val profile=File(root,"recommendation-model/preferences").walkTopDown().single{it.isFile&&it.extension=="jsonl"}
  fun latest()=JSONObject(profile.readLines().last())
  val before=latest();val model=RecommendationModelAdapter(root,proof.getString("user"));assertTrue(model.load())
  val catalog=listOf("jamendo_api_1204669","jamendo_api_1141572","jamendo_api_1161940").map{Track(it,it,"observed catalog")}
  model.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,proof.getString("drive"))
  val after=latest();assertEquals(before.getInt("learnedCount")+1,after.getInt("learnedCount"))
  assertNotEquals(before.getJSONArray("vector").toString(),after.getJSONArray("vector").toString())
  val bytes=profile.readBytes()
  val reopened=RecommendationModelAdapter(root,proof.getString("user"));assertTrue(reopened.load())
  reopened.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,proof.getString("drive"))
  assertArrayEquals(bytes,profile.readBytes())
  proof.put("verifiedActualSummaryLearned",true).put("preferenceLearnedCountBefore",before.getInt("learnedCount"))
   .put("preferenceLearnedCountAfter",after.getInt("learnedCount")).put("duplicateSummaryChangedProfile",false)
  proofFile.writeText(proof.toString(2))
 }
}
