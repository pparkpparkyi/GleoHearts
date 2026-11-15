package com.example.gleo_type

import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Native Galaxy size, isolated synthetic history/photos. Actual Media3 has a separate regression test. */
class V13UiIntegrationTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun capture(name:String){ui.onRoot().captureToImage().asAndroidBitmap().let{b->
  val file=File(context.cacheDir,"v13-captures/$name.png").apply{parentFile!!.mkdirs()}
  file.outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
 @Test fun fiveTabsReviewPhotoFreeSaveCalendarAndThreeStageSheet(){
  val root=File(context.cacheDir,"v13-ui-${UUID.randomUUID()}").apply{mkdirs()}
  val tracks=listOf("A","B","C").map{Track("SYNTHETIC_$it","격리 UI 음악 $it","합성 메타데이터")}
  val date=LocalDate.now();val record=DriveRecord("SYNTHETIC_RUN/DRIVE","DRIVE",date.toString()+"T00:00:00Z",date.toString()+"T00:03:00Z",tracks.map{it.id},3000.0,true,null,true)
  val repo=LocalArchiveRepository(File(root,"archive"));val photoStore=ArchivePhotoStore(context,File(root,"photos"))
  val photo=photoStore.importSelected(Uri.parse("content://${context.packageName}.v13-photo-fixture/blue"))
  repo.save(Pin("SYNTHETIC_MEMORY",date.toString()+"T00:00:00Z",null,tracks[0],null,emptyList(),"합성 사진 기록",attachments=listOf(photo),coverAttachmentId=photo.id))
  val plans=LocalMusicPlanRepository(File(root,"plans.json"),"SYNTHETIC_USER")
  val history=object:DriveHistoryRepository{override fun refresh()=HistorySnapshot(listOf(record))}
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi(true,tracks[0]));override val catalog=MutableStateFlow(tracks)
   override fun toggle(){};override fun play(track:Track){};override fun next(){};override fun previous(){};override fun seek(position:Long){}
  }
  val name=root.name;context.getSharedPreferences(name,0).edit().putBoolean("dark",true).putString("date",date.toString()).commit()
  val restore=StateRestorationTester(ui)
  restore.setContent {GleoScreen(player,history,repo,"SYNTHETIC_USER",{null},{},{},{},name,
   ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),mobileLayout=true,photoStore=photoStore,musicPlans=plans)}
  ui.waitUntil(10000){ui.onAllNodesWithTag("v12_open_review").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("home_sheet").assertExists()
  ui.onNodeWithTag("sheet_handle").performClick();ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"확장"))
  ui.onNodeWithTag("sheet_handle").performClick();ui.onNodeWithTag("sheet_handle").performClick()
  for(tab in 0..4){ui.onNodeWithTag("nav_$tab").performClick();ui.waitForIdle();capture("tab-$tab-dark")}
  ui.onNodeWithTag("nav_0").performClick();ui.onNodeWithTag("v12_open_review").performScrollTo().performClick()
  capture("review-before")
  ui.onNodeWithTag("mobile_review_cards").performTouchInput{swipeLeft(durationMillis=300)}
  ui.onNodeWithTag("mobile_review_previous").performScrollTo().performClick()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performScrollTo().performTouchInput{down(center);moveBy(Offset(0f,-18f));cancel()}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertExists()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),400)}
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist()
  restore.emulateSavedInstanceStateRestore()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist()
  ui.onNodeWithTag("mobile_review_undo").performScrollTo().performClick()
  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performScrollTo().performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),400)}
  ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed().performClick()
  ui.onNodeWithTag("v13_photo_editor").assertExists();capture("photo-skip")
  ui.onNodeWithTag("v13_photo_save").performClick()
  ui.waitUntil(10000){repo.load().size==3}
  assertEquals(setOf("SYNTHETIC_B","SYNTHETIC_C"),repo.load().filter{it.heartsReview!=null}.map{it.track!!.id}.toSet())
  ui.onNodeWithTag("nav_3").assertIsSelected();capture("hearts-after-save")
  ui.onNodeWithTag("v12_pin_grid").performClick()
  ui.onNodeWithTag("hearts_search").performTextInput("격리 UI 음악")
  ui.onAllNodesWithContentDescription("Hearts 상세 열기").assertCountEquals(3)
  assertEquals(3,LocalArchiveRepository(File(root,"archive")).load().size)
  // Save a future plan using an explicit future date; the UI contract never writes listening/history.
  ui.onNodeWithTag("nav_2").performClick()
  ui.onNodeWithText((date.plusDays(1).dayOfMonth).toString(),useUnmergedTree=true).performClick()
  ui.onNodeWithTag("v13_plan_open").performScrollTo().performClick()
  ui.onNodeWithTag("v13_plan_all").performScrollTo().performClick()
  ui.onNodeWithTag("v13_plan_save").performClick()
  ui.waitUntil(10000){plans.load().size==1}
  assertEquals(3,plans.load().single().musicIds.size);assertEquals(1,history.refresh().drives.size)
  capture("calendar-plan")
 }
}
