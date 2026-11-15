package com.example.gleo_type

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DrivingRecommendationUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun replayFixtureShowsExperimentalRuleThenNormalUnknownFallback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"driving-ui-${UUID.randomUUID()}")
        val dir=File(root,"models/clap500");dir.mkdirs()
        for(n in listOf("manifest.json","embeddings.bin")) File(context.filesDir,"models/clap500/$n").copyTo(File(dir,n))
        var input=DrivingRecommendationInput("SYNTHETIC","drive",10000,1,"available",null,0.0,true,false,
            "stopped",9000, .9,10000,"synthetic",ContextInputSource.REPLAY,"synthetic_ui_fixture")
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_UI_PROFILE",PlaybackMappingVerifier(context)::verify,
            drivingContext={input})
        adapter.initialize(setOf("indie"),setOf("energetic"))
        val history=MemoryRecommendationHistory()
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalDrivingUx provides DrivingUx(true,false,0,"synthetic_fixture")) {
            RecommendationPanel(listOf(Track("jamendo_0010","Is It Right?","Lower Loveday")),
                PlaybackUi(connected=true,catalogReady=true),emptySet(),emptySet(),history,"drive",{},{},{},{},adapter)
        } } }
        ui.waitUntil(15000) { history.load().size==1 }
        ui.onNodeWithTag("driving_recommendation_status").assertTextContains("실험 규칙",substring=true)
        assertTrue(history.load().last().drivingDecision!!.applied)
        val persisted=LocalRecommendationHistory(File(root,"recommendations"));persisted.append(history.load().last())
        assertEquals(history.load().last(),persisted.load().last())
        ui.runOnIdle { input=input.copy(quality="unknown",fallbackReason="stale_speed") }
        ui.onNodeWithTag("regenerate_recommendations").performScrollTo().performClick()
        ui.waitUntil(15000) { history.load().size==2 }
        ui.onNodeWithTag("driving_recommendation_status").assertTextContains("현재 차량 정보 없이 취향 기반으로 추천")
        assertFalse(history.load().last().drivingDecision!!.applied)
    }
}
