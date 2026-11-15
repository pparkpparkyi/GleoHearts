package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Reads the actual previous Media3 collection's cache archive. No user/archive/journal writes. */
class MobileObservedHeartsReadTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 @Test fun observedEndedSessionIsSharedByHomeCalendarAndHearts() {
  val context=ui.activity
  val legacy=File(context.filesDir,"diagnostics/car-v10-step5/result.json")
  val root:File;val localId:String;val record:DriveRecord;val snapshot:HistorySnapshot;val fixtureSource:String
  if(legacy.exists()) {
   val row=JSONObject(legacy.readText());val key=row.getString("runId")+"/"+row.getString("driveSessionId")
   root=File(context.cacheDir,"hearts-step5-actual").listFiles().orEmpty().filter{it.isDirectory}
    .single {LocalArchiveRepository(it).load().any{p->p.sourceDriveKey==key}}
   localId=File(context.noBackupFilesDir,"local-profile-id").readText()
   snapshot=JsonlHistoryRepository(File(context.filesDir,"events"),localId).refresh()
   record=snapshot.drives.single{it.key==key};fixtureSource="LEGACY_STEP5_ACTUAL_ARCHIVE_READ_ONLY"
  } else {
   // Exact current real-playback proof replaces a missing old test artifact, never fabricated history.
   val audio=File(context.cacheDir,"audio-registry-approved-samples")
   val proof=JSONObject(File(context.cacheDir,"v12-window-captures/real-media3-ui-proof.json").readText())
   assertTrue(proof.getBoolean("engineProgressObserved"));val music=proof.getString("musicId");val playback=proof.getString("playbackSessionId")
   localId=File(audio,"identity/local-profile-id").readText()
   snapshot=JsonlHistoryRepository(File(audio,"journal/events"),localId).refresh()
   record=snapshot.drives.single{it.playbacks.any{p->p.playbackSessionId==playback && p.musicId==music}}
   assertNotNull(record.endedAt)
   root=File(context.cacheDir,"step6-current-observed-${UUID.randomUUID()}")
   LocalArchiveRepository(root).save(ArchiveProjection.pinFromDrive(record,Track(music,music,"실제 재생 ID · 메타데이터 미제공"),emptyList(),"격리 수용 검증 · 실제 Media3 기록",record.endedAt!!))
   fixtureSource="CURRENT_REAL_MEDIA3_CACHE_JOURNAL_NEW_ISOLATED_ARCHIVE_FIXTURE"
  }
  val key=record.key;val repo=LocalArchiveRepository(root);val pins=repo.load();val original=File(root,"pins.json").readBytes()
  assertNotNull(record.endedAt)
  val actual=snapshot.copy(drives=listOf(record),listeningWallMs=record.listeningWallMs,listeningAvailable=record.hasListening)
  val name="step6-observed-"+UUID.randomUUID()
  context.getSharedPreferences(name,0).edit().putString("date",record.date.toString()).putString("month",record.date.toString().take(7)).putBoolean("dark",false).commit()
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi(connected=false));override val catalog=MutableStateFlow(pins.mapNotNull{it.track}.distinctBy{it.id})
   override fun play(track:Track){};override fun toggle(){};override fun previous(){};override fun next(){};override fun seek(position:Long){}
  }
  ui.setContent {Box(Modifier.requiredSize(402.dp,874.dp)){GleoScreen(player,object:DriveHistoryRepository{override fun refresh()=actual},repo,localId,{null},{},{},{},name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(context.cacheDir,name),"READ_ONLY_TEST"),mobileLayout=true)}}
  val diagnostic=File(context.filesDir,"diagnostics/car-v10-step6/observed").apply{mkdirs()}
  for(tab in listOf(0,2,3)) {
   ui.onNodeWithTag("nav_$tab").performClick()
   if(tab==3)ui.onNodeWithTag("v12_pin_grid").performClick()
   if(tab==3)ui.waitUntil(15000){ui.onAllNodesWithTag("hearts_search").fetchSemanticsNodes().isNotEmpty()}
   ui.onNodeWithTag("app_root").captureToImage().asAndroidBitmap().let {b->File(diagnostic,"tab$tab.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
  }
  ui.onAllNodesWithContentDescription("Hearts 상세 열기").onFirst().performClick();ui.onNodeWithText("주행 기록 연결됨").assertExists()
  ui.onNodeWithText("닫기").performClick();ui.onNodeWithTag("nav_2").performClick()
  ui.onNodeWithTag("v12_month_view").performClick()
  ui.onNodeWithTag("calendar_day_${record.date!!.dayOfMonth}").performScrollTo().performClick()
  ui.onAllNodesWithContentDescription("Hearts 상세 열기").onFirst().assertExists()
  assertEquals(pins.size,ArchiveProjection.onDate(actual,pins,record.date!!).second.size)
  assertArrayEquals(original,File(root,"pins.json").readBytes())
  File(diagnostic,"result.json").writeText(JSONObject().put("source",fixtureSource).put("uiPlayer","DISCONNECTED_READ_ONLY_ADAPTER")
   .put("driveKey",key).put("pins",pins.size).put("sharedViews",3).put("archiveUnchanged",true).toString(2))
 }
}
