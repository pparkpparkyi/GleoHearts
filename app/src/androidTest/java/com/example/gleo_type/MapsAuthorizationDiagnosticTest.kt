package com.example.gleo_type

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Diagnostic completion is NOT tile-rendering success. Synthetic pins remain in cache. */
@org.junit.Ignore("Google Maps diagnostics paused; mobile now defaults to a local image")
class MapsAuthorizationDiagnosticTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 @Test fun observeHomeAndHeartsAuthorizationWithoutWritingUserRecords() {
  val root=File(ui.activity.cacheDir,"maps-auth-${UUID.randomUUID()}")
  val repo=LocalArchiveRepository(File(root,"archive"))
  repo.save(Pin("SYNTHETIC_MAP_A","2026-11-13T00:00:00Z",null,Track("SYNTHETIC_MAP_A","인증 진단용 음악","합성 데이터"),Place(37.5665,126.978),emptyList(),""))
  val file=File(root,"archive/pins.json");val before=file.readBytes()
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi());override val catalog=MutableStateFlow(emptyList<Track>())
   override fun toggle(){};override fun play(track:Track){};override fun previous(){};override fun next(){};override fun seek(position:Long){}
  }
  val history=object:DriveHistoryRepository{override fun refresh()=HistorySnapshot()}
  val captures=File(ui.activity.cacheDir,"maps-auth-captures").apply{mkdirs()};val facts=JSONArray()
  ui.setContent{GleoScreen(player,history,repo,"SYNTHETIC_USER",{null},{},{},{},root.name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),
   recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(root,"no-model"),"SYNTHETIC_USER"),mobileLayout=true)}
  fun observed(name:String) {
   ui.onNodeWithTag("archive_google_map").assertExists()
   // Drive the app's Compose timeout deterministically, without assuming SDK authorization.
   ui.mainClock.advanceTimeBy(16000)
   val settled=try {
    ui.waitUntil(10000){ui.onAllNodesWithText("지도를 불러오지 못했습니다",substring=true).fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("지도 연결 중…").fetchSemanticsNodes().isEmpty()};true
   } catch(_:ComposeTimeoutException){false}
   val failed=ui.onAllNodesWithText("지도를 불러오지 못했습니다",substring=true).fetchSemanticsNodes().isNotEmpty()
   ui.onRoot().captureToImage().asAndroidBitmap().let{b->File(captures,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   facts.put(JSONObject().put("screen",name).put("mapViewPresent",true).put("timeoutMessageVisible",failed).put("uiObservationSettled",settled)
    .put("tileRenderingVerified",false).put("tileVerification","SCREENSHOT_AND_AUTH_LOG_REQUIRED").put("source","SYNTHETIC_CACHE_ARCHIVE"))
  }
  ui.waitUntil(15000){ui.onAllNodesWithTag("archive_google_map").fetchSemanticsNodes().isNotEmpty()}
  observed("home")
  ui.onNodeWithTag("archive_google_map").performTouchInput{swipe(Offset(width*.7f,height*.3f),Offset(width*.3f,height*.3f),400)}
  ui.onNodeWithTag("archive_google_map").performTouchInput{
   pinch(Offset(width*.4f,height*.3f),Offset(width*.6f,height*.3f),Offset(width*.2f,height*.3f),Offset(width*.8f,height*.3f),400)
  }
  // Input delivery alone does not prove map movement/zoom when authorization is rejected.
  ui.onNodeWithTag("nav_3").performClick();ui.onNodeWithTag("v12_pin_map").assertIsDisplayed()
  observed("hearts")
  ui.onNodeWithTag("sheet_handle").performClick()
  ui.onNodeWithTag("home_sheet").assertIsDisplayed()
  assertArrayEquals(before,file.readBytes())
  File(captures,"facts.json").writeText(JSONObject().put("observations",facts).put("panAndPinchInputDelivered",true)
   .put("cameraMovementVerified",false).put("isolatedArchiveUnchanged",true).toString(2))
 }
}
