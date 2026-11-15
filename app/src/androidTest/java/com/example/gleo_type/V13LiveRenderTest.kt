package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.ui.gleohearts.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Isolated UI fixtures, NOT actual SDK / audio / cross-device observations. */
class V13LiveRenderTest {
    @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"v13-live-captures").apply{mkdirs()}
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        ui.runOnIdle {File(dir,"$name-window.json").writeText(ui.activity.windowFacts().toString(2))}
    }
    @Test fun mediaCommandDoesNotOptimisticallyDeclareSuccessAndUnknownPinIsBlocked(){
        val flow=MutableStateFlow(PlaybackUi(true,Track("SYNTHETIC_ID","격리 곡","테스트")))
        var calls=0
        val port=object:PlaybackPort {
            override val state=flow;override val catalog=MutableStateFlow(listOf(flow.value.track!!))
            override fun toggle(){calls++};override fun play(track:Track){};override fun next(){};override fun previous(){};override fun seek(position:Long){}
        }
        ui.setContent { val p by flow.collectAsState()
            Box(Modifier.fillMaxSize()) {V13LiveCarPlayer(port,p,null,DrivingUx(),"TEST_CATALOG · 합성 UI fixture",
                object:CarV10Actions {override fun toggle()=port.toggle()},null,false,null,{})}
        }
        ui.onNodeWithText("▶",useUnmergedTree=true).performScrollTo().performClick()
        assertEquals(1,calls);assertFalse(flow.value.playing)
        ui.onNodeWithTag("car_runtime_heart").performScrollTo().assertIsNotEnabled()
        ui.runOnIdle { flow.value=flow.value.copy(playing=true) }
        ui.onNodeWithText("Ⅱ",useUnmergedTree=true).assertExists()
        ui.onNodeWithTag("v13_play_recommendation").performScrollTo().assertIsNotEnabled()
        capture("car-fixture")
    }
    @Test fun finalFiveTabsKeepCanonicalHostSelection(){
        ui.setContent { var tab by remember {mutableStateOf(GleoTab.HOME)}
            GleoMobileFrame(tab,{tab=it}) {Text("선택:${tab.name}")}
        }
        listOf(0 to "HOME",2 to "CALENDAR",1 to "MUSIC",3 to "PIN",4 to "MY").forEach { (id,name) ->
            ui.onNodeWithTag("nav_$id").performClick();ui.onNodeWithText("선택:$name").assertExists()
        }
        ui.onNodeWithText("pin!").assertExists()
        capture("mobile-frame-fixture")
    }
}
