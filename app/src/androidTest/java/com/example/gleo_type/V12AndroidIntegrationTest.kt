package com.example.gleo_type

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.DrivingRecommendationInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** UI fixtures are labelled synthetic and remain in cache. Real Media3 is tested separately. */
class V12AndroidIntegrationTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private val tracks=listOf("A","B","C").map{Track("SYNTHETIC_$it","격리 UI 음악 $it","합성 메타데이터")}
 private val record=DriveRecord("SYNTHETIC_RUN/DRIVE","DRIVE","2026-11-13T00:00:00Z","2026-11-13T00:03:00Z",tracks.map{it.id},3000.0,true,null,true)
 private fun capture(name:String) {
  val file=File(ui.activity.cacheDir,"v12-captures/$name.png").apply{parentFile!!.mkdirs()}
  ui.onRoot().captureToImage().asAndroidBitmap().let{b->file.outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 }
 @Test fun homeReviewSwipeUndoSaveRestoreAndSyncDisclosureUseExistingIsolatedRepository() {
  val root=File(ui.activity.cacheDir,"v12-review-${UUID.randomUUID()}");val archive=LocalArchiveRepository(File(root,"archive"))
  val history=object:DriveHistoryRepository{override fun refresh()=HistorySnapshot(listOf(record))}
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi(true,tracks[0]));override val catalog=MutableStateFlow(tracks)
   override fun toggle(){state.value=state.value.copy(playing=!state.value.playing)}
   override fun play(track:Track){state.value=state.value.copy(track=track,playing=true)}
   override fun next(){};override fun previous(){};override fun seek(position:Long){}
  }
  val name="v12-${UUID.randomUUID()}";ui.activity.getSharedPreferences(name,0).edit().putBoolean("dark",false).commit()
  val restore=StateRestorationTester(ui)
  restore.setContent {Box(Modifier.requiredSize(402.dp,874.dp)){GleoScreen(player,history,archive,"SYNTHETIC_USER",{null},{},{},{},name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(root,"no-model"),"SYNTHETIC_USER"),mobileLayout=true)}}
  ui.waitUntil(10000){ui.onAllNodesWithTag("v12_open_review").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("v12_open_review").performScrollTo().performClick();ui.onNodeWithTag("mobile_v12_review").assertExists();capture("review-before")
  ui.onNodeWithTag("mobile_review_cards").performTouchInput{swipeLeft(durationMillis=350)}
  ui.onNodeWithTag("mobile_review_previous").assertIsEnabled()
  ui.onNodeWithTag("mobile_review_cards").performTouchInput{swipeRight(durationMillis=350)}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertIsDisplayed()
  ui.onNodeWithTag("mobile_review_next").assertIsEnabled().performScrollTo().performClick()
  ui.onNodeWithTag("mobile_review_previous").assertIsEnabled().performClick()
  ui.onNodeWithTag("mobile_review_edit").performClick()
  ui.onNodeWithText("저장 후보 재검토 중",substring=true).assertExists()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performScrollTo()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performTouchInput{down(center);moveBy(Offset(0f,-20f));cancel()}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertExists()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),450)}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist();restore.emulateSavedInstanceStateRestore()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist()
  ui.onNodeWithTag("mobile_review_undo").performScrollTo().performClick();ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertExists()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performScrollTo()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),450)}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist()
  ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed().performClick();ui.waitUntil(10000){archive.load().size==2}
  assertEquals(setOf("SYNTHETIC_B","SYNTHETIC_C"),archive.load().map{it.track!!.id}.toSet())
  assertTrue(archive.load().all{it.heartsReview?.excludedMusicIds==listOf("SYNTHETIC_A")})
  assertEquals(3,history.refresh().drives.single().tracks.size)
  ui.onNodeWithTag("mobile_review_remember").assertIsNotEnabled()
  capture("review-saved")
  ui.onNodeWithTag("mobile_review_edit").assertIsDisplayed()
  ui.onNodeWithTag("nav_0").performClick()
  for(tab in 0..4){ui.onNodeWithTag("nav_$tab").performClick();ui.waitForIdle();capture("tab$tab-light")}
  ui.onNodeWithTag("v12_sync_status").performScrollTo().performClick()
  ui.onNodeWithText("서버·기기 페어링·전송 미연결.",substring=true).assertExists()
  ui.onNodeWithTag("dark_mode").performScrollTo().performClick()
  ui.onNodeWithTag("nav_0").performClick();capture("home-dark")
  assertEquals(2,LocalArchiveRepository(File(root,"archive")).load().size)
  File(ui.activity.cacheDir,"review-final-archive-path.txt").writeText(File(root,"archive").absolutePath)
 }
 @Test fun carWindowHasNoFakeListeningAndUsesObservedPlaybackStateWithRestrictedContext() {
  var playback by mutableStateOf(PlaybackUi(true,tracks[0]));var toggles=0
  var details by mutableStateOf(false)
  ui.setContent {Box(Modifier.fillMaxSize()){MaterialTheme {
   if(details)CarV12ContextPanel(null,DrivingUx(),{details=false}) else CarV12Player(CarV10UiState(playback,DrivingUx()),object:CarV10Actions{override fun toggle(){toggles++}},{details=true})
  }}}
  ui.onNodeWithText("공식 음성 미연결").assertExists();ui.onNodeWithText("듣고 있어요").assertDoesNotExist()
  ui.onNodeWithTag("car_v12_context").assertIsDisplayed().assertIsNotEnabled()
  ui.runOnIdle{playback=playback.copy(playing=true,playbackSessionId="SYNTHETIC_PLAYBACK")}
  ui.onNodeWithTag("car_v12_playback_status").assertTextEquals("Media3 재생 확인");capture("car-v12-window-simulated-state")
  ui.runOnIdle{details=true};ui.onNodeWithTag("v12_context_restricted").assertExists();capture("car-context-restricted")
  assertEquals(0,toggles)
 }
 @Test fun sixContextCellsUseSnapshotSourcesAndShowMissingValuesOnlyInIsolatedParkedPreview() {
  val now=SystemClock.elapsedRealtime()
  val snapshot=ContextSnapshotProvider("SYNTHETIC_USER","SYNTHETIC_RUN",ContextObservationStore{null},
   {drive->DrivingRecommendationInput("SYNTHETIC_RUN",drive,now,null,"unknown","not_observed")},{now},"isolated_test").capture(null)
  val ux=DrivingUx(true,false,0,"CarUxRestrictionsManager",now)
  ui.setContent {Box(Modifier.fillMaxSize()){MaterialTheme{CarV12ContextPanel(snapshot,ux,{})}}}
  listOf("위치","목적지","운전","도로","시간·날씨","발화").forEach{ui.onNodeWithText(it).assertExists()}
  ui.onAllNodesWithText("미수신 · 추정하지 않음").assertCountEquals(5)
  assertEquals(ContextFreshness.MISSING,snapshot.timeWeather.getValue("weather").freshness)
  capture("car-six-context-isolated-preview")
 }
 @Test fun installedHomeReadsRealLocalArchivesWithoutStartingCollectionOrCreatingRecords() {
  val c=ui.activity;val archive=LocalArchiveRepository(File(c.filesDir,"archive"))
  val path=File(c.filesDir,"archive/pins.json");val before=path.takeIf{it.exists()}?.readBytes()
  val id=File(c.noBackupFilesDir,"local-profile-id").readText()
  val history=JsonlHistoryRepository(File(c.filesDir,"events"),id)
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi(false));override val catalog=MutableStateFlow(emptyList<Track>())
   override fun play(track:Track){};override fun toggle(){};override fun previous(){};override fun next(){};override fun seek(position:Long){}
  }
  val name="v12-readonly-${UUID.randomUUID()}";c.getSharedPreferences(name,0).edit().putBoolean("dark",false).commit()
  ui.setContent {GleoScreen(player,history,archive,id,{null},{fail("must not start collection")},{fail("must not stop collection")},{},name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(c.cacheDir,name),id),mobileLayout=true)}
  ui.waitUntil(10000){ui.onAllNodesWithTag("home_hearts_counts").fetchSemanticsNodes().isNotEmpty()}
  // Observe the real SDK viewport after its asynchronous tile request, not a renderer stub.
  Thread.sleep(17000);capture("installed-home-real-local-data-map-unconfirmed")
  InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap ->
   val f=File(c.cacheDir,"v12-window-captures/installed-home-real-local-data-device.png").apply{parentFile!!.mkdirs()}
   f.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  }
  if(before==null)assertFalse(path.exists()) else assertArrayEquals(before,path.readBytes())
  val lazy=GleoApplication::class.java.getDeclaredField("container\$delegate").apply{isAccessible=true}.get(c.application) as Lazy<*>
  assertFalse(lazy.isInitialized())
 }
 @Test fun markerBitmapPhotoFallbackAndExactCountAreIndependentFromBlockedMapAuthentication() {
  val photo=android.graphics.Bitmap.createBitmap(30,30,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.CYAN)}
  val pins=(1..3).map{Pin("SYNTHETIC_$it","2026-11-13T00:00:00Z",null,null,Place(37.5,127.0),emptyList(),"")}
  val count=HeartMapProjection.groups(pins).single().pins.size;assertEquals(3,count)
  val withPhoto=heartMarkerBitmap(count,photo,false);val fallback=heartMarkerBitmap(count,null,false)
  assertEquals(android.graphics.Color.CYAN,withPhoto.getPixel(70,80));assertFalse(withPhoto.sameAs(fallback))
  assertFalse(withPhoto.sameAs(heartMarkerBitmap(1,photo,false)))
  for((name,b) in listOf("marker-photo-count-3" to withPhoto,"marker-fallback-count-3" to fallback)) {
   val f=File(ui.activity.cacheDir,"v12-captures/$name.png").apply{parentFile!!.mkdirs()};f.outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  }
 }
 @Test fun carRecommendationCarriesTheExactScoredSnapshotWithoutChangingExistingScores() {
  val now=SystemClock.elapsedRealtime();val user="SYNTHETIC_V12_${UUID.randomUUID()}";var captures=0
  val snapshot=ContextSnapshotProvider(user,"SYNTHETIC_RUN",ContextObservationStore{null},
   {d->DrivingRecommendationInput("SYNTHETIC_RUN",d,now,null,"unknown","not_observed")},{now},"isolated_test").capture(null)
  val root=File(ui.activity.cacheDir,"audio-registry-approved-samples/model-fixture")
  val model=com.example.gleo_type.recommendation.RecommendationModelAdapter(root,user,contextSnapshotProvider={captures++;snapshot},contextElapsedMs={now},contextRunId={"SYNTHETIC_RUN"})
  assertTrue(model.load());model.initialize(setOf("pop"),setOf("happy"))
  val candidates=listOf("jamendo_api_1204669","jamendo_api_1141572","jamendo_api_1161940").map{Track(it,it,"catalog identity test")}
  val result=model.carSnapshot(candidates,emptySet(),null)
  assertEquals(RecommendationOrigin.AI_MODEL,result.run.origin);assertSame(snapshot,result.contextSnapshot);assertEquals(1,captures)
  val previous=model.recommend(candidates,MusicScope.THREE,emptySet(),emptySet(),0,null,null,snapshot=snapshot)
  assertEquals(previous.scores,result.run.scores);assertEquals(previous.musicIds,result.run.musicIds)
  assertEquals(snapshot.appRunId,result.run.drivingDecision?.input?.runId)
 }
}
