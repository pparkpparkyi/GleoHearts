package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class StaticHeartsMapTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private class Player:PlaybackPort {
  override val state=MutableStateFlow(PlaybackUi());override val catalog=MutableStateFlow(emptyList<Track>())
  override fun play(track:Track){};override fun toggle(){};override fun previous(){};override fun next(){};override fun seek(position:Long){}
 }
 private fun capture(name:String) {
  val dir=File(ui.activity.cacheDir,"static-map-captures").apply{mkdirs()}
  ui.waitForIdle()
  val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
   ?: error("Device screenshot unavailable")
  bitmap.let{b->File(dir,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 }
 @Test fun cardsOpenExistingPhotoDateDetailAndRestoreWithoutArchiveMutation() {
  val root=File(ui.activity.cacheDir,"static-map-${UUID.randomUUID()}").apply{mkdirs()}
  val repo=LocalArchiveRepository(File(root,"archive"))
  val photo=File(root,"synthetic-photo.png")
  android.graphics.Bitmap.createBitmap(80,60,android.graphics.Bitmap.Config.ARGB_8888).apply{
   eraseColor(android.graphics.Color.rgb(80,110,150));photo.outputStream().use{compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};recycle()}
  // Calendar initially selects today. Keep this fixture on the device's current day.
  val at=java.time.Instant.now().toString()
  repo.save(Pin("SYNTHETIC_LOCATED",at,null,Track("SYNTHETIC_A","합성 장소 음악","격리 아티스트"),Place(37.5665,126.978),emptyList(),"테스트 메모",
   attachments=listOf(ArchiveAttachment("PHOTO_A","photo",android.net.Uri.fromFile(photo).toString(),"격리 로컬 사진","image/png"))))
  repo.save(Pin("SYNTHETIC_UNKNOWN",at,null,Track("SYNTHETIC_B","합성 위치 미확인 음악","격리 아티스트"),null,emptyList(),""))
  repo.save(Pin("SYNTHETIC_DELETED",at,null,null,null,emptyList(),""));repo.delete("SYNTHETIC_DELETED")
  val original=File(root,"archive/pins.json").readBytes()
  val restore=StateRestorationTester(ui)
  restore.setContent{GleoScreen(Player(),object:DriveHistoryRepository{override fun refresh()=HistorySnapshot()},repo,"SYNTHETIC_USER",{null},{},{},{},root.name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),
   recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(root,"no-model"),"SYNTHETIC_USER"),mobileLayout=true)}
  ui.waitUntil(10000){ui.onAllNodesWithTag("static_heart_SYNTHETIC_LOCATED").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("hearts_static_map").assertExists();ui.onNodeWithTag("archive_google_map").assertDoesNotExist()
  ui.onNodeWithTag("static_map_disclosure").assertIsDisplayed();capture("home")
  ui.onNodeWithTag("static_heart_SYNTHETIC_LOCATED").performClick()
  ui.onNodeWithText("기록: $at").assertExists() // existing detail falls back to the actual saved date
  ui.onNodeWithText("저장: $at").assertExists()
  ui.waitUntil(10000){ui.onAllNodesWithTag("heart_local_photo_PHOTO_A").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("heart_local_photo_PHOTO_A").performScrollTo().assertIsDisplayed();capture("detail-photo")
  restore.emulateSavedInstanceStateRestore();ui.onNodeWithText("gleo Hearts · 저장한 순간").assertExists()
  ui.onNodeWithText("닫기").performClick()
  ui.onNodeWithTag("nav_3").performClick();ui.onNodeWithTag("v12_pin_map").performClick();ui.onNodeWithTag("hearts_static_map").assertExists()
  ui.onNodeWithTag("static_place_cards").performScrollToNode(hasTestTag("static_heart_SYNTHETIC_UNKNOWN"))
  ui.onNodeWithTag("static_heart_SYNTHETIC_UNKNOWN").performClick();ui.onNodeWithText("gleo Hearts · 저장한 순간").assertExists()
  capture("hearts-detail-unknown");ui.onNodeWithText("닫기").performClick();capture("hearts")
  ui.onNodeWithTag("static_heart_SYNTHETIC_DELETED").assertDoesNotExist()
  ui.onNodeWithTag("v12_pin_grid").performClick()
  ui.onNodeWithTag("hearts_search").performScrollTo().performTextInput("위치 미확인 음악")
  ui.onAllNodesWithContentDescription("Hearts 상세 열기").assertCountEquals(1)
  ui.onNodeWithTag("hearts_search").performTextClearance()
  ui.onAllNodesWithContentDescription("Hearts 상세 열기").assertCountEquals(2)
  ui.onNodeWithTag("nav_2").performClick()
  ui.onNodeWithText("합성 위치 미확인 음악").assertExists();capture("calendar")
  assertArrayEquals(original,File(root,"archive/pins.json").readBytes())
  assertNull(repo.load().single{it.id=="SYNTHETIC_UNKNOWN"}.place)
 }
 @Test fun emptyImageDoesNotSignalGoogleReadiness() {
  var loaded=0
  ui.setContent{MaterialTheme{MobileArchiveHome(HistorySnapshot(),emptyList(),PlaybackUi(),0,{},{},{},{},Player(),onMapLoaded={loaded++})}}
  ui.onNodeWithTag("static_map_empty").assertIsDisplayed();ui.onNodeWithTag("archive_google_map").assertDoesNotExist()
  assertEquals(0,loaded);capture("empty")
 }
 @Test fun unavailableLocalAndRemotePhotosKeepTheirMetadata() {
  assertTrue(runCatching{ArchiveAttachment("REMOTE","photo","https://example.invalid/not-requested.png","외부 사진","image/png")}.isFailure)
  val pin=Pin("SYNTHETIC_MISSING","2026-11-13T00:00:00Z",null,null,null,emptyList(),"",attachments=listOf(
   ArchiveAttachment("MISSING","photo",android.net.Uri.fromFile(File(ui.activity.cacheDir,"missing-${UUID.randomUUID()}.png")).toString(),"없는 로컬 사진","image/png")))
  ui.setContent{MaterialTheme{Column{HeartLocalPhotos(pin)}}}
  ui.onAllNodesWithText("사진을 표시할 수 없습니다",substring=true).assertCountEquals(1)
  ui.onNodeWithTag("heart_local_photo_REMOTE").assertDoesNotExist()
  assertEquals(1,pin.attachments.size)
 }
}
