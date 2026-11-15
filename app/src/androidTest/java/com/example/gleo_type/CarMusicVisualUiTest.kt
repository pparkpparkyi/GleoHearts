package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CarMusicVisualUiTest {
    @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
    private val candidate=CarMusicCandidate("SYNTHETIC_MODEL","SYNTHETIC_MEDIA","합성 UI 음악","테스트 메타데이터",emptySet(),setOf("acoustic"),null,null,null,null,null,true,
        "SYNTHETIC_CALLBACK_ONLY","unknown","no_sdk","OWNED_TEST_NOT_REAL_ALBUM")
    @Test fun recommendationGeometryThemesAndOwnedImageFallback() {
        var w by mutableIntStateOf(440);var h by mutableIntStateOf(317);var dark by mutableStateOf(true);var image by mutableStateOf<ByteArray?>(null);var taps=0
        ui.setContent {Box(Modifier.requiredSize(w.dp,h.dp)){CarV10Player(CarV10UiState(
            PlaybackUi(true,Track("SYNTHETIC_MEDIA","합성 UI 음악","테스트 아티스트"),artwork=image),dark=dark,
            sourceDisclosure="합성 UI 전용 · 실제 음원 아님",recommendation="TEST_CATALOG · 합성 버튼",
            suggestion=candidate,background=CarBackgroundPolicy.resolve(CarAcousticFeatures("SYNTHETIC_MODEL",136.0,moods=setOf("acoustic")),dark)),
            object:CarV10Actions{override fun playRecommendation(){taps++}})}}
        val out=File(ui.activity.cacheDir,"car-music-visual-${UUID.randomUUID()}").apply{mkdirs()}
        for((label,size) in listOf("wf" to (440 to 317),"ws" to (558 to 334),"we" to (558 to 586),"s129" to (644 to 423),"s146" to (688 to 459),"s17" to (860 to 584)))for(d in listOf(true,false)) {
            ui.runOnIdle{w=size.first;h=size.second;dark=d}
            ui.onNodeWithTag("car_recommendation").assertIsDisplayed().assertIsEnabled()
            ui.onNodeWithTag("car_toggle").assertIsDisplayed()
            val a=ui.onNodeWithTag("car_recommendation").fetchSemanticsNode().boundsInRoot
            val b=ui.onNodeWithTag("car_toggle").fetchSemanticsNode().boundsInRoot
            assertTrue("recommendation/transport overlap",a.bottom<=b.top)
            val bmp=ui.onNodeWithTag("car_v10_player").captureToImage().asAndroidBitmap()
            File(out,"${label}_${d}.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
        }
        ui.onNodeWithTag("car_recommendation").performClick();ui.runOnIdle{assertEquals(1,taps)}
        val repository=CarMusicPresentationRepository(ui.activity,{null},{emptyList()})
        val owned=repository.artwork(candidate);assertNotNull(owned)
        ui.runOnIdle{image=owned}
        ui.waitUntil(5000){ui.onAllNodesWithContentDescription("현재 곡 앨범아트").fetchSemanticsNodes().isNotEmpty()}
        ui.runOnIdle{image=byteArrayOf(1,2,3)}
        ui.waitUntil(5000){ui.onAllNodesWithContentDescription("현재 곡 앨범아트").fetchSemanticsNodes().isEmpty()}
        File(ui.activity.filesDir,"diagnostics/car-v10-step4/visual-path.txt").apply{parentFile!!.mkdirs();writeText(out.absolutePath)}
    }
}
