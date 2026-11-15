package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

class CarV10UiTest {
    @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
    private val track=Track("SYNTHETIC_A","UI 검증용 곡명","합성 메타데이터")
    private fun state(safe:Boolean=true)=CarV10UiState(PlaybackUi(true,track),
        if(safe)DrivingUx(true,false,0,"SYNTHETIC_UI") else DrivingUx(),sourceDisclosure="격리 UI 테스트 · 실제 재생/저장 아님")
    private fun record()=DriveRecord("SYNTHETIC_RUN/DRIVE","DRIVE","2026-11-11T00:00:00Z","2026-11-11T00:01:00Z",
        listOf("SYNTHETIC_A","SYNTHETIC_B","SYNTHETIC_C"),0.0,false,null,true)
    private val tracks get()=record().tracks.map { Track(it,it,"합성 테스트") }
    @Test fun playerInitialEmptyPausePlayAndCommandsAreOnlyCallbacks() {
        var s by mutableStateOf(CarV10UiState(sourceDisclosure="격리 UI 테스트"));var toggles=0;var next=0;var previous=0
        ui.setContent{CarV10Player(s,object:CarV10Actions {
            override fun toggle(){toggles++};override fun next(){next++};override fun previous(){previous++}
        })}
        ui.onNodeWithTag("car_toggle").assertIsNotEnabled()
        ui.runOnIdle{s=state()}
        ui.onNodeWithTag("car_toggle").performClick();ui.onNodeWithTag("car_next").performClick();ui.onNodeWithTag("car_previous").performClick()
        ui.runOnIdle{assertEquals(1,toggles);assertEquals(1,next);assertEquals(1,previous);s=s.copy(playback=s.playback.copy(playing=true))}
        ui.onNodeWithContentDescription("일시정지").assertExists()
        ui.runOnIdle{s=s.copy(playback=s.playback.copy(buffering=true))};ui.onNodeWithText("버퍼링").assertExists()
        ui.runOnIdle{s=s.copy(playback=s.playback.copy(connected=false))};ui.onNodeWithTag("car_next").assertIsNotEnabled()
        // This debug Activity and pure surface never initialize the real collection container.
        val app=ui.activity.application as GleoApplication
        val lazy=GleoApplication::class.java.getDeclaredField("container\$delegate").apply{isAccessible=true}.get(app) as Lazy<*>
        assertFalse(lazy.isInitialized())
    }
    @Test fun dragCancelExcludeRestoreAndSavedStateKeepObservedOriginal() {
        val restore=StateRestorationTester(ui)
        restore.setContent{CarV10Review(record(),tracks,state(),object:CarV10Actions{})}
        ui.onNodeWithTag("car_card_SYNTHETIC_A").performTouchInput{down(center);moveBy(Offset(0f,-150f));cancel()}
        ui.onNodeWithTag("car_card_SYNTHETIC_A").assertExists()
        ui.onNodeWithTag("car_card_SYNTHETIC_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),400)}
        ui.waitUntil(5000){ui.onAllNodesWithTag("car_card_SYNTHETIC_A").fetchSemanticsNodes().isEmpty()}
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("car_card_SYNTHETIC_A").assertDoesNotExist()
        ui.onNodeWithTag("car_restore").performScrollTo().performClick()
        ui.onNodeWithTag("car_card_SYNTHETIC_A").assertExists()
        ui.onNodeWithTag("car_remember").performScrollTo().performClick()
        ui.onNodeWithText("저장소 미연결 · 아직 저장하지 않았습니다").assertExists()
        assertEquals(3,record().tracks.size)
    }
    @Test fun unknownDrivingStateAndEmptyMusicPreventEditing() {
        var s by mutableStateOf(state(false));var r by mutableStateOf<DriveRecord?>(record())
        ui.setContent{CarV10Review(r,tracks,s,object:CarV10Actions{})}
        ui.onNodeWithTag("car_edit").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("car_remember").assertIsNotEnabled()
        ui.runOnIdle{r=record().copy(tracks=emptyList());s=state()}
        ui.onNodeWithTag("car_review_empty").assertExists();ui.onNodeWithTag("car_remember").assertIsNotEnabled()
    }
    @Test fun saveAcknowledgementFailureRetryAndHomeAreExplicitTestAdapter() {
        var callback:((CarV10SaveResult)->Unit)?=null;var homes=0
        val actions=object:CarV10Actions {
            override val remember:((String,List<String>,(CarV10SaveResult)->Unit)->Unit)={key,ids,done->
                assertEquals(record().key,key);assertEquals(3,ids.size);callback=done }
            override fun home(){homes++}
        }
        ui.setContent{CarV10Review(record(),tracks,state(),actions)}
        ui.onNodeWithTag("car_remember").performScrollTo().performClick()
        ui.onNodeWithText("저장 중").assertExists();ui.onNodeWithTag("car_home").assertDoesNotExist()
        ui.runOnIdle{callback!!(CarV10SaveResult(null,"합성 저장 실패"))}
        ui.onNodeWithText("합성 저장 실패").assertExists()
        ui.onNodeWithTag("car_remember").performClick()
        ui.runOnIdle{callback!!(CarV10SaveResult("SYNTHETIC_RECEIPT_ONLY","테스트 응답 확인 · 실제 저장 아님"))}
        ui.onNodeWithTag("car_home").performScrollTo().performClick();ui.runOnIdle{assertEquals(1,homes)}
    }
    @Test fun constrainedSizesLightDarkScreenshotsAndVisibleTouchTargets() {
        var w by mutableIntStateOf(440);var h by mutableIntStateOf(317);var dark by mutableStateOf(true)
        ui.setContent{Box(Modifier.requiredSize(w.dp,h.dp)){CarV10Player(state().copy(dark=dark),object:CarV10Actions{})}}
        val root=File(ui.activity.cacheDir,"car-v10-layout-${UUID.randomUUID()}").apply{mkdirs()}
        val results=JSONArray()
        for((name,size) in listOf("s17" to (860 to 584),"s146" to (688 to 459),"s129" to (644 to 423),
            "w_standard" to (558 to 334),"w_extended" to (558 to 586),"wf" to (440 to 317)))for(d in listOf(false,true)) {
            ui.runOnIdle{w=size.first;h=size.second;dark=d}
            for(tag in listOf("car_previous","car_toggle","car_next"))ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            ui.onNodeWithTag("car_track_title").assertIsDisplayed()
            val density=ui.activity.resources.displayMetrics.density
            val boxes=listOf("car_previous","car_toggle","car_next").map { ui.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            boxes.forEach { assertTrue("touch height",it.height/density>=91f);assertTrue("touch width",it.width/density>=64f) }
            assertTrue(boxes.zipWithNext().all{(a,b)->a.right<=b.left})
            val bitmap=ui.onNodeWithTag("car_v10_player").captureToImage().asAndroidBitmap()
            assertTrue(kotlin.math.abs((w*density).toInt()-bitmap.width)<=1)
            assertTrue(kotlin.math.abs((h*density).toInt()-bitmap.height)<=1)
            results.put(JSONObject().put("case",name).put("dark",d).put("requestedDp","${w}x$h")
                .put("imagePx","${bitmap.width}x${bitmap.height}").put("minimumTouchHeightDp",boxes.minOf{it.height}/density))
            File(root,"${name}_${if(d)"dark" else "light"}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        File(ui.activity.filesDir,"diagnostics/car-v10-layout-pointer.json").apply{parentFile!!.mkdirs();writeText(root.absolutePath)}
        var facts=JSONObject();ui.runOnIdle{facts=ui.activity.windowFacts()}
        File(root,"layout-facts.json").writeText(facts.put("cases",results).put("source","SYNTHETIC_UI_NO_SDK").toString(2))
    }
    @Test fun horizontalBrowsingChangesScrollWithoutRemovingObservedMusic() {
        val r=record().copy(tracks=(1..8).map{"SYNTHETIC_$it"})
        ui.setContent{Box(Modifier.requiredSize(440.dp,800.dp)){CarV10Review(r,emptyList(),state(),object:CarV10Actions{})}}
        fun position()=ui.onNodeWithTag("car_review_cards").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val start=position()
        ui.onNodeWithTag("car_review_cards").performTouchInput{swipeLeft()};ui.waitForIdle()
        val left=position();assertTrue(left>start)
        ui.onNodeWithTag("car_review_cards").performTouchInput{swipeRight()};ui.waitForIdle()
        assertTrue(position()<left);assertEquals(8,r.tracks.size)
    }
    @Test fun interruptedSaveRestoresAsUnconfirmedAndIgnoresOldCallback() {
        var done:((CarV10SaveResult)->Unit)?=null
        val actions=object:CarV10Actions {
            override val remember:((String,List<String>,(CarV10SaveResult)->Unit)->Unit)={_,_,callback->done=callback}
        }
        val restore=StateRestorationTester(ui)
        restore.setContent{CarV10Review(record(),tracks,state(),actions)}
        ui.onNodeWithTag("car_remember").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("저장 결과 미확인 · 원본 기록 유지").assertExists()
        ui.runOnIdle{done!!(CarV10SaveResult("OLD_TEST_RECEIPT","오래된 응답"))}
        ui.onNodeWithTag("car_home").assertDoesNotExist()
    }
}
