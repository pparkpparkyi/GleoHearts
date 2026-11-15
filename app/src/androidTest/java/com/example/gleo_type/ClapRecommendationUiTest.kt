package com.example.gleo_type

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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

class ClapRecommendationUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun verifiedAiCardScoresExclusionRestorationAndDrivingRestrictions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"clap-ui-synthetic-${UUID.randomUUID()}")
        val modelDir=File(root,"models/clap500"); modelDir.mkdirs()
        for(name in listOf("manifest.json","embeddings.bin"))
            File(context.filesDir,"models/clap500/$name").copyTo(File(modelDir,name))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_UI_TEST",PlaybackMappingVerifier(context)::verify)
        adapter.initialize(setOf("indie"),setOf("energetic"))
        val history=MemoryRecommendationHistory()
        val excluded=mutableStateOf(emptySet<String>())
        val ux=mutableStateOf(DrivingUx(true,false,0,"synthetic_fixture"))
        var played:String?=null
        val tracks=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"),Track("jamendo_0016","Bad Side","Rxbyn"))
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalDrivingUx provides ux.value) {
            Surface { Box(Modifier.fillMaxSize()) {
                RecommendationPanel(tracks,PlaybackUi(connected=true,catalogReady=true),emptySet(),excluded.value,history,null,
                    { played=it.id },{ excluded.value=excluded.value+it.id },{ excluded.value=emptySet() },{},adapter)
            } }
        } } }
        ui.waitUntil(15000) { history.load().size==1 }
        assertEquals(RecommendationOrigin.AI_MODEL,history.load().single().origin)
        assertEquals(listOf("jamendo_0010"),history.load().single().musicIds)
        ui.onNodeWithText("CLAP 임베딩 취향 추천 · Gleo 미연결").assertExists()
        ui.onNodeWithTag("recommendation_play_jamendo_0010").performScrollTo().performClick()
        assertEquals("jamendo_0010",played)
        ui.runOnIdle { excluded.value=setOf("jamendo_0010") }
        ui.onNodeWithTag("recommendations_empty").assertExists()
        ui.onNodeWithTag("restore_recommendations").performScrollTo().performClick()
        ui.onNodeWithTag("recommendation_play_jamendo_0010").assertExists()
        ui.onNodeWithTag("regenerate_recommendations").performScrollTo().performClick()
        ui.waitUntil(15000) { history.load().size==2 }
        assertEquals(history.load().first().musicIds,history.load().last().musicIds)
        assertEquals(0,File(root,"recommendation-model").walkTopDown().filter { it.extension=="jsonl" }
            .map { org.json.JSONObject(it.readLines().last()).getInt("learnedCount") }.sum())
        ui.runOnIdle { ux.value=DrivingUx() }
        ui.onNodeWithTag("regenerate_recommendations").assertDoesNotExist()
    }
    @Test fun explicitOnboardingWithoutIdentityEvidenceKeepsTestCatalog() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"clap-ui-unmapped-${UUID.randomUUID()}")
        val modelDir=File(root,"models/clap500"); modelDir.mkdirs()
        for(name in listOf("manifest.json","embeddings.bin"))
            File(context.filesDir,"models/clap500/$name").copyTo(File(modelDir,name))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_UNMAPPED_UI_TEST") // No verified mapping provider.
        val history=MemoryRecommendationHistory()
        val tracks=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"))
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalDrivingUx provides DrivingUx(true,false,0,"synthetic_fixture")) {
            Surface { Box(Modifier.fillMaxSize()) {
                RecommendationPanel(tracks,PlaybackUi(connected=true,catalogReady=true),emptySet(),emptySet(),history,null,
                    {},{},{},{},adapter)
            } }
        } } }
        ui.waitUntil(15000) { history.load().isNotEmpty() }
        ui.onNodeWithText("장르 선택").performScrollTo().performClick()
        ui.onNodeWithText("indie").performScrollTo().performClick()
        ui.onNodeWithText("분위기 선택").performScrollTo().performClick()
        ui.onNodeWithText("energetic").performScrollTo().performClick()
        ui.onNodeWithText("선택 취향 저장").performScrollTo().performClick()
        ui.waitUntil(15000) { history.load().size==2 }
        assertTrue(history.load().all { it.origin==RecommendationOrigin.TEST_CATALOG })
        ui.onNodeWithText("테스트 목록 · AI 추천 미사용 / Gleo 미연결").assertExists()
        assertEquals(0,adapter.availability(setOf("jamendo_0010")).playableCount)
    }
    @Test fun insufficientVerifiedCandidatesOfferExplicitTestFallback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"catalog-fallback-${UUID.randomUUID()}")
        val dir=File(root,"models/clap500");dir.mkdirs()
        for(name in listOf("manifest.json","embeddings.bin")) File(context.filesDir,"models/clap500/$name").copyTo(File(dir,name))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_FALLBACK",PlaybackMappingVerifier(context)::verify)
        adapter.initialize(setOf("indie"),setOf("energetic"))
        val history=MemoryRecommendationHistory();val excluded=mutableStateOf(emptySet<String>())
        val tracks=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"),Track("jamendo_0016","Bad Side","Rxbyn"))
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalDrivingUx provides DrivingUx(true,false,0,"synthetic_fixture")) {
            Surface { Box(Modifier.fillMaxSize()) { RecommendationPanel(tracks,PlaybackUi(connected=true,catalogReady=true),emptySet(),excluded.value,
                history,null,{},{},{},{},adapter) } }
        } } }
        ui.waitUntil(15000) { history.load().size==1 }
        ui.runOnIdle { excluded.value=setOf("jamendo_0010") }
        ui.onNodeWithTag("fallback_test_catalog").performScrollTo().performClick()
        ui.waitUntil(15000) { history.load().size==2 }
        assertEquals(RecommendationOrigin.TEST_CATALOG,history.load().last().origin)
        assertEquals(listOf("jamendo_0016"),history.load().last().musicIds)
        assertNull(history.load().last().modelVersion)
        ui.onNodeWithText("테스트 목록 · AI 추천 미사용 / Gleo 미연결").assertExists()
    }
}
