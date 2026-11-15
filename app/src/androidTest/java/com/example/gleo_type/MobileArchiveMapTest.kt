package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.event.core.*
import com.example.gleo_type.event.location.LocationDto
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.send.JsonlEventSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** All synthetic rows, archives and artwork remain in cache/map-test-UUID, never files/archive. */
class MobileArchiveMapTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private class Player:PlaybackPort {
  override val state=MutableStateFlow(PlaybackUi())
  override val catalog=MutableStateFlow(emptyList<Track>())
  override fun toggle(){};override fun play(track:Track){};override fun next(){};override fun previous(){};override fun seek(position:Long){}
 }
 private fun root()=File(ui.activity.cacheDir,"map-test-${UUID.randomUUID()}").apply{mkdirs()}
 private fun pin(id:String,place:Place?)=Pin(id,"2026-11-13T00:00:00Z",null,Track(id,"격리 음악 $id","테스트"),place,emptyList(),"")
 private fun capture(name:String) {
  val file=File(ui.activity.cacheDir,"map-test-captures/$name.png").apply{parentFile!!.mkdirs()}
  ui.onRoot().captureToImage().asAndroidBitmap().let{b->file.outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 }
 @org.junit.Ignore("Google Maps paused; preserved SDK test requires separate authorization work")
 @Test fun emptyArchiveStillLoadsRealGoogleMap() {
  val repo=LocalArchiveRepository(File(root(),"archive"));val loaded=AtomicBoolean()
  ui.setContent {var level by remember{mutableIntStateOf(0)};MaterialTheme{MobileArchiveHome(HistorySnapshot(),repo.load(),PlaybackUi(),level,{level=it},{},{},{},Player(),onMapLoaded={loaded.set(true)},mapRenderer=ArchiveMapRenderer.GOOGLE_MAPS)}}
  try {ui.waitUntil(60000){loaded.get()};assertTrue(repo.load().isEmpty())} finally {capture("empty-real-map")}
 }
 @org.junit.Ignore("Google Maps paused; preserved SDK test requires separate authorization work")
 @Test fun syntheticCoordinatesRenderPhotoMarkersWithoutProductionWrites() {
  val root=root();val repo=LocalArchiveRepository(File(root,"archive"))
  val photo=File(root,"test-photo.png")
  android.graphics.Bitmap.createBitmap(120,120,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(30,120,140));photo.outputStream().use{compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};recycle()}
  val attachment=ArchiveAttachment("test-photo","photo",UriFromFile(photo),"격리 테스트 이미지","image/png")
  repo.save(pin("a",Place(37.5665,126.978)).copy(attachments=listOf(attachment)))
  repo.save(pin("b",Place(37.5665,126.978)))
  repo.save(pin("c",Place(37.56,126.99)))
  repo.save(pin("unlocated",null));repo.save(pin("deleted",Place(37.57,126.97)));repo.delete("deleted")
  val before=File(root,"archive/pins.json").readBytes();val loaded=AtomicBoolean()
  assertEquals(listOf(2,1),HeartMapProjection.groups(repo.load()).map{it.pins.size})
  assertEquals(listOf("unlocated"),HeartMapProjection.unlocated(repo.load()).map{it.id})
  ui.setContent {var level by remember{mutableIntStateOf(0)};MaterialTheme{MobileArchiveHome(HistorySnapshot(),repo.load(),PlaybackUi(),level,{level=it},{},{},{},Player(),onMapLoaded={loaded.set(true)},mapRenderer=ArchiveMapRenderer.GOOGLE_MAPS)}}
  try {ui.waitUntil(60000){loaded.get()};ui.waitForIdle()} finally {capture("synthetic-real-map-markers")}
  assertArrayEquals(before,File(root,"archive/pins.json").readBytes())
 }
 @Test fun markerSelectionAndThreeSheetStatesKeepUnknownCoordinatesInList() {
  val pins=listOf(pin("a",Place(37.5665,126.978)),pin("unknown",null));var opened:String?=null
  val restoration=StateRestorationTester(ui)
  restoration.setContent {var level by rememberSaveable{mutableIntStateOf(0)};MaterialTheme{MobileArchiveHome(HistorySnapshot(),pins,PlaybackUi(),level,{level=it},{},{opened=it.id},{},Player(),mapContent={groups,select,_->
   Column(Modifier.padding(top=120.dp)) {Text("TEST ONLY · 지도 렌더러 대역");groups.forEach{g->Button(onClick={select(g.id)},modifier=Modifier.testTag("test_marker_${g.id}")){Text(g.id)}}}
  })}}
  ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"축소"))
  ui.onNodeWithTag("sheet_handle").performClick()
  ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"중간"))
  ui.onNodeWithTag("sheet_handle").performClick()
  ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"확장"))
  ui.onNodeWithTag("sheet_handle").performClick();ui.onNodeWithTag("test_marker_a").performClick()
  ui.onNodeWithText("이 장소의 음악 · 1개").assertExists()
  restoration.emulateSavedInstanceStateRestore()
  ui.onNodeWithText("이 장소의 음악 · 1개").assertExists()
  ui.onNodeWithTag("home_heart_a").performScrollTo().performClick();assertEquals("a",opened)
  ui.onNodeWithText("전체 Hearts 보기").performScrollTo().performClick()
  ui.onNodeWithTag("home_heart_unknown").performScrollTo().assertExists()
 }
 @Test fun locationEnvelopeSessionMusicAndArchiveCoordinatesRoundTrip()=runBlocking {
  val root=root();val sender=JsonlEventSender(File(root,"events"),runId="SYNTHETIC",localUserId="test",inputOrigin="mock",environment="isolated_test")
  val sessions=DriveSessionManager{"DRIVE"};sessions.start()
  val factory=EventFactory(sessionProvider=sessions,elapsedRealtime={1234L})
  sender.append("session_started","DRIVE","2026-11-13T00:00:00Z","TEST","test").getOrThrow()
  val event=factory.create(LocationPayload(MusicSummaryDto("a","1000","true","PLAY"),LocationDto("37.5665","126.978",null,null,"1234")))
  assertEquals("DRIVE",event.driveSessionId);assertEquals("PLAY",event.observation!!.playbackSessionId)
  sender.send(event).getOrThrow()
  sender.append("listening_interval","DRIVE","2026-11-13T00:00:01Z","TEST","test",mapOf("playbackSessionId" to "PLAY","musicId" to "a","intervalIndex" to "1","creditedWallMs" to "1000")).getOrThrow()
  sender.append("session_stopped","DRIVE","2026-11-13T00:00:02Z","TEST","test").getOrThrow()
  val history=JsonlHistoryRepository(File(root,"events"),"test").refresh();val record=history.drives.single()
  assertEquals(Place(37.5665,126.978),record.place);assertEquals(listOf("a"),record.tracks)
  val repo=LocalArchiveRepository(File(root,"archive"));repo.saveReview(record,listOf(Track("a","격리 음악","테스트")),listOf("a"),"test","Asia/Seoul","2026-11-13T00:00:03Z")
  val json=JSONObject(File(root,"archive/pins.json").readText()).getJSONArray("pins").getJSONObject(0)
  assertEquals(37.5665,json.getJSONObject("place").getDouble("latitude"),.000001)
  assertEquals("SYNTHETIC/DRIVE",repo.load().single().sourceDriveKey)
  assertEquals(1,HeartMapProjection.groups(LocalArchiveRepository(File(root,"archive")).load()).size)
  Unit
 }
 private fun UriFromFile(file:File)=android.net.Uri.fromFile(file).toString()
}
