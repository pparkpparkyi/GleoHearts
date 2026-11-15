package com.example.gleo_type

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Galaxy window, local large-font/small-window simulations, and real Activity rotation.
 * No global density/font settings, production container, SDK collection, or user archive writes. */
class V12WindowLayoutTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private data class Viewport(val name:String,val width:Int?=null,val height:Int?=null,val font:Float?=null,val car:Boolean=false)
 @Test fun actualInsetsSmallWindowsLargeFontsAndRotationKeepActionsReachable() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(context.cacheDir,"v12-window-${UUID.randomUUID()}").apply{mkdirs()}
  val captures=File(context.cacheDir,"v12-window-captures").apply{mkdirs()}
  val tracks=(1..3).map{Track("SYNTHETIC_LAYOUT_$it","화면 검증 음악 $it","격리 테스트")}
  val record=DriveRecord("SYNTHETIC_WINDOW/DRIVE","DRIVE","2026-11-13T00:00:00Z","2026-11-13T00:01:00Z",tracks.map{it.id},0.0,false,null,true)
  val history=object:DriveHistoryRepository{override fun refresh()=HistorySnapshot(listOf(record))}
  val archive=LocalArchiveRepository(File(root,"archive"))
  val player=object:PlaybackPort {
   override val state=MutableStateFlow(PlaybackUi(true,tracks[0]));override val catalog=MutableStateFlow(tracks)
   override fun toggle(){state.value=state.value.copy(playing=!state.value.playing)}
   override fun play(track:Track){state.value=state.value.copy(track=track,playing=true)}
   override fun previous(){};override fun next(){};override fun seek(position:Long){}
  }
  var viewport by mutableStateOf(Viewport("actual-portrait"))
  val facts=JSONArray()
  val originalOrientation=ui.activity.requestedOrientation
  fun capture(name:String) {
   val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
   File(captures,"$name-compose.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
   val device=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
   if(device!=null){File(captures,"$name-device.png").outputStream().use{device.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};device.recycle()}
  }
  fun bounds(tag:String):JSONObject {
   val b=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
   return JSONObject().put("tag",tag).put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)
  }
  fun tab(index:Int,name:String) {
   ui.onNodeWithTag("nav_$index").performClick();ui.waitForIdle()
   for(i in 0..4)ui.onNodeWithTag("nav_$i").assertIsDisplayed()
   val row=ui.activity.windowFacts().put("case",name).put("viewport",viewport.toString()).put("nav",bounds("nav_0"))
   if(index==0){ui.onNodeWithTag("home_sheet").assertIsDisplayed();row.put("sheet",bounds("home_sheet"))}
   if(index==1){ui.onNodeWithTag("v12_music_start").assertIsDisplayed();row.put("musicStart",bounds("v12_music_start"))
    if(viewport.name=="actual-landscape")ui.onNodeWithText(tracks[0].title).assertIsDisplayed()
   }
   facts.put(row);capture(name)
  }
  CarV10TestActivity.recreationContent={
   val density=LocalDensity.current;val config=LocalConfiguration.current
   BoxWithConstraints(Modifier.fillMaxSize()) {
    val width=viewport.width?.dp?.let{minOf(it,maxWidth)} ?: maxWidth
    val height=viewport.height?.dp?.let{minOf(it,maxHeight)} ?: maxHeight
    val localConfig=Configuration(config).apply{screenWidthDp=width.value.toInt();screenHeightDp=height.value.toInt()}
    CompositionLocalProvider(LocalDensity provides Density(density.density,viewport.font ?: density.fontScale),LocalConfiguration provides localConfig) {
     Box(Modifier.size(width,height)) {key(viewport.name) {
      if(viewport.car)Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {CarV12Player(CarV10UiState(player.state.value,DrivingUx()),object:CarV10Actions{override fun toggle(){player.toggle()}},{})}
      else GleoScreen(player,history,archive,"SYNTHETIC_LAYOUT",{null},{},{},{},root.name+viewport.name,
       ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(root,"no-model"),"SYNTHETIC_LAYOUT"),mobileLayout=true)
     }}
    }
   }
  }
  try {
   ui.activityRule.scenario.recreate();ui.waitForIdle()
   for(i in 0..4)tab(i,"actual-portrait-tab$i")
   ui.onNodeWithTag("nav_0").performClick();ui.onNodeWithTag("v12_open_review").performScrollTo().performClick()
   ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed();capture("actual-portrait-review")
   ui.onNodeWithTag("mobile_review_edit").assertIsDisplayed()
  ui.onNodeWithTag("nav_0").performClick()
   for(case in listOf(Viewport("small-320x640-font150",320,640,1.5f),Viewport("actual-font200",font=2f))) {
    ui.runOnIdle{viewport=case};ui.waitForIdle()
    for(i in 0..4)tab(i,"${case.name}-tab$i")
    ui.onNodeWithTag("nav_0").performClick();ui.onNodeWithTag("v12_open_review").performScrollTo().performClick()
    ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed()
    val button=ui.onNodeWithTag("mobile_review_remember").fetchSemanticsNode().boundsInRoot
    val nav=ui.onNodeWithTag("nav_0").fetchSemanticsNode().boundsInRoot
    assertTrue("Review action must remain above tabs",button.bottom<=nav.top+1)
    facts.put(ui.activity.windowFacts().put("case",case.name+"-review").put("reviewAction",bounds("mobile_review_remember")))
    capture(case.name+"-review")
   }
   ui.runOnIdle{viewport=Viewport("actual-landscape")}
   ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
   ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
   ui.waitForIdle();tab(0,"actual-landscape-home");tab(1,"actual-landscape-music")
   ui.onNodeWithTag("nav_0").performClick()
   ui.onNodeWithTag("v12_open_review").performScrollTo().performClick()
   ui.onNodeWithTag("mobile_review_edit").assertIsDisplayed()
   ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed()
   capture("actual-landscape-review")
   facts.put(ui.activity.windowFacts().put("case","actual-landscape-review").put("reviewAction",bounds("mobile_review_remember")))
   ui.onNodeWithTag("nav_0").performClick()
   ui.runOnIdle{viewport=Viewport("car-handheld-landscape-preview",car=true)};ui.waitForIdle()
   val density=ui.activity.resources.displayMetrics.density
   for(tag in listOf("car_v12_previous","car_v12_toggle","car_v12_next")) {
    ui.onNodeWithTag(tag).assertIsDisplayed();val r=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    assertTrue("transport height $tag",r.height/density>=91f);assertTrue("transport width $tag",r.width/density>=64f)
   }
   facts.put(ui.activity.windowFacts().put("case","car-handheld-landscape-preview").put("toggle",bounds("car_v12_toggle")))
   capture("car-handheld-landscape-preview")
   File(captures,"window-facts.json").writeText(facts.toString(2))
   assertTrue(archive.load().isEmpty())
  } finally {
   File(captures,"window-facts-partial.json").writeText(facts.toString(2))
   ui.activityRule.scenario.onActivity{it.requestedOrientation=originalOrientation}
   CarV10TestActivity.recreationContent=null
  }
 }
}
