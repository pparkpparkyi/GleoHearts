package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.RegistryPlaybackTestService
import com.example.gleo_type.recommendation.RecommendationModelAdapter
import com.example.gleo_type.ui.app.*
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Same production PlaybackPort/Media3 service code; only the Session component and stores are isolated. */
class Step8cUiLearningTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 private fun <T> request(action:()->ListenableFuture<T>):T {lateinit var f:ListenableFuture<T>;main{f=action()};return f.get(30,TimeUnit.SECONDS)}
 private fun await(label:String,predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+20000
  while(SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(50)};fail(label)}
 @Test fun ordinaryUiLikeCreatesCbrAndChangesNextRecommendation() {
  val root=File(context.cacheDir,"audio-registry-approved-samples")
  RegistryPlaybackTestService.testRoot=root
  val component=ComponentName(context,RegistryPlaybackTestService::class.java)
  val browser=request{MediaBrowser.Builder(context,SessionToken(context,component)).buildAsync()}
  val c=RegistryPlaybackTestService.activeContainer!!
  val fixture=File(context.cacheDir,"step8c-learning-${UUID.randomUUID()}").apply{mkdirs()}
  // Small model/metadata fixture copy only. No MP3 copying, transfer, download or retraining.
  File(root,"model-fixture/models/clap500").copyRecursively(File(fixture,"models/clap500"))
  val adapter=RecommendationModelAdapter(fixture,c.localIdentity.id,contextSnapshotProvider={c.contextSnapshots.capture(it)},contextRunId={c.runId},
   operationalRuntime=com.example.gleo_type.recommendation.OperationalRecommendationRuntime(fixture,c.localIdentity.id,
    {context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}},
    {context.assets.open("audio-assets/catalog-v1.json").bufferedReader().use{it.readText()}}))
  assertTrue(adapter.load());adapter.initialize(setOf("pop"),setOf("happy"))
  val history=MemoryRecommendationHistory()
  lateinit var port:MediaPlaybackConnection
  main {c.startCollection();browser.volume=0f;port=MediaPlaybackConnection(context,c,component)}
  try {
   await("Existing verified canonical assets are discoverable"){port.state.value.catalogReady && port.catalog.value.any{it.id=="jamendo_api_1204669"}}
   ui.setContent {GleoScreen(port,JsonlHistoryRepository(File(root,"journal/events"),c.localIdentity.id),LocalArchiveRepository(File(fixture,"archive")),
    c.localIdentity.id,c.driveSessions::currentDriveSessionId,{fail("No user collector")},{fail("No user collector")},{},fixture.name,
    ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationHistory=history,recommendationModelAdapter=adapter,mobileLayout=true)}
   ui.onNodeWithTag("nav_1").performClick()
   ui.waitUntil(20000){ui.onAllNodesWithTag("v12_music_start").fetchSemanticsNodes().isNotEmpty()}
   val recommendation=history.load().last();assertEquals(RecommendationOrigin.AI_MODEL,recommendation.origin)
   assertNotNull(recommendation.contextEvidence);assertNotNull(recommendation.fusionDecision)
   val expected=recommendation.musicIds.first()
   assertTrue(expected in setOf("jamendo_api_1204669","jamendo_api_1141572","jamendo_api_1161940"))
   ui.onNodeWithTag("v12_music_start").assertIsDisplayed().performClick()
   await("Actual Media3 and same PlaybackPort observe playing/progress/session") {
    var real=false;main{real=browser.isPlaying && browser.currentPosition>=250 && browser.currentMediaItem?.mediaId==expected && browser.playerError==null}
    real && port.state.value.playing && port.state.value.track?.id==expected && port.state.value.playbackSessionId!=null
   }
   val playbackId=port.state.value.playbackSessionId!!
   ui.onNodeWithText("Media3 재생 중").assertExists()
   val captures=File(context.cacheDir,"v12-window-captures").apply{mkdirs()}
   ui.onRoot().captureToImage().asAndroidBitmap().let{b->File(captures,"real-media3-music-compose.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   i.uiAutomation.takeScreenshot()?.let{b->File(captures,"real-media3-music-device.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   ui.onNodeWithTag("player_toggle").performClick();await("Actual pause appears in UI port"){!port.state.value.playing}
   await("Same canonical music/playback session has STARTED and PAUSED events") {
    val rows=File(root,"journal/events").listFiles().orEmpty().filter{it.extension=="jsonl"}.flatMap{it.readLines().filter(String::isNotBlank).map(::JSONObject)}
     .filter{it.optString("runId")==c.runId && it.optString("playbackSessionId")==playbackId && it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==expected}
    rows.any{it.optJSONObject("event")?.optString("label")=="STARTED"} && rows.any{it.optJSONObject("event")?.optString("label")=="PAUSED"}
   }
   val beforeScore=recommendation.scores.getValue(expected)
   ui.onNodeWithTag("recommendation_like").performScrollTo().performClick()
   ui.waitUntil(15000){ui.onAllNodesWithText("좋아요 저장",substring=true).fetchSemanticsNodes().isNotEmpty()}
   val cases=com.example.gleo_type.recommendation.ExperienceCaseRepository.forUser(fixture,c.localIdentity.id)
   assertEquals(1,cases.cases().size)
   val saved=cases.cases();assertTrue(port.likeObserved(adapter,expected,playbackId,c.driveSessions.currentDriveSessionId()))
   assertEquals(saved,cases.cases())
   ui.onNodeWithTag("regenerate_recommendations").performScrollTo().performClick()
   ui.waitUntil(15000){history.load().size>=2}
   val next=history.load().last();val row=next.fusionDecision!!.scores.single{it.musicId==expected}
   assertTrue(row.cbr>0);assertTrue(next.scores.getValue(expected)>beforeScore)
   val displayedScore="점수 %.3f".format(next.scores.getValue(expected))
   ui.waitUntil(15000){ui.onAllNodesWithText(displayedScore,substring=true).fetchSemanticsNodes().isNotEmpty()}
   ui.onNodeWithText(displayedScore,substring=true).performScrollTo().assertIsDisplayed()
   val evidence=JSONObject().put("source","GALAXY_ORDINARY_UI_ISOLATED_SERVICE_AND_STORES").put("fixtureRoot",fixture.absolutePath)
    .put("user",c.localIdentity.id).put("runId",c.runId).put("drive",c.driveSessions.currentDriveSessionId()).put("playback",playbackId)
    .put("musicId",expected).put("beforeScore",beforeScore).put("afterScore",next.scores[expected]).put("cbr",row.cbr)
    .put("beforeRank",recommendation.musicIds.indexOf(expected)+1).put("afterRank",next.musicIds.indexOf(expected)+1)
    .put("cases",saved.size).put("displayedScoreMatchesNextRecommendation",true).put("duplicateLikeChangedCases",false).put("pid",android.os.Process.myPid())
   File(context.cacheDir,"step8c-learning-proof.json").writeText(evidence.toString(2))
   i.uiAutomation.takeScreenshot()?.let{b->File(context.cacheDir,"step8c-learning.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}

  } finally {
   main {port.close();browser.stop();browser.clearMediaItems();c.stopCollection()}
   await("Queue cleared"){var empty=false;main{empty=browser.mediaItemCount==0};empty}
   val destroyed=RegistryPlaybackTestService.destructions
   main{browser.release()};context.stopService(Intent(context,RegistryPlaybackTestService::class.java))
   await("Isolated service destroyed"){RegistryPlaybackTestService.destructions>destroyed}
  }
  runBlocking { withTimeout(10000) { c.awaitSyncPreparation() } }
  assertTrue(c.reviewSync.outbox.states().values.any{it==com.example.gleo_type.sync.OutboxState.pending})
  val lazy=GleoApplication::class.java.getDeclaredField("container\$delegate").apply{isAccessible=true}.get(context.applicationContext) as Lazy<*>
  assertFalse(lazy.isInitialized())
 }
}
