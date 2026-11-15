package com.example.gleo_type

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic metadata, real existing AtomicFile repository; never edits user archive. */
class MobileStep6UiTest {
    @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
    private class Player:PlaybackPort {
        val track=Track("SYNTHETIC_MOBILE","합성 음악","테스트 아티스트")
        override val state=MutableStateFlow(PlaybackUi(true,track,duration=100000))
        override val catalog=MutableStateFlow(listOf(track))
        override fun toggle(){state.value=state.value.copy(playing=!state.value.playing)}
        override fun play(track:Track){state.value=state.value.copy(track=track,playing=true)}
        override fun next(){};override fun previous(){};override fun seek(position:Long){}
    }
    @Test fun fiveTabsEditDeleteRestoreAndSharedHistoryWithLightDarkCaptures() {
        val context=ui.activity;val name="step6-"+UUID.randomUUID();val root=File(context.cacheDir,name)
        val repo=LocalArchiveRepository(File(root,"archive"));val player=Player()
        val record=DriveRecord("SYNTHETIC_RUN/DRIVE","DRIVE","2026-11-10T23:59:00Z","2026-11-11T00:01:00Z",listOf(player.track.id),1000.0,true,null,true)
        repo.save(ArchiveProjection.pinFromDrive(record,player.track,listOf("합성"),"처음 메모","2026-11-12T00:00:00Z"))
        val prefs=context.getSharedPreferences(name,0);prefs.edit().putString("date",record.date.toString()).putString("month",record.date.toString().take(7)).putBoolean("dark",false).commit()
        val restoration=StateRestorationTester(ui)
        restoration.setContent {Box(Modifier.requiredSize(402.dp,874.dp)){GleoScreen(player,object:DriveHistoryRepository{override fun refresh()=HistorySnapshot(listOf(record))},repo,"SYNTHETIC_LOCAL",{null},{},{},{},name,
            ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),recommendationModelAdapter=com.example.gleo_type.recommendation.RecommendationModelAdapter(File(root,"no-model"),"SYNTHETIC_LOCAL"),mobileLayout=true)}}
        val diagnostic=File(context.filesDir,"diagnostics/car-v10-step6/mobile").apply{mkdirs()}
        fun capture(tab:Int,theme:String){ui.onNodeWithTag("nav_$tab").performClick();ui.waitForIdle()
            ui.onNodeWithTag("app_root").captureToImage().asAndroidBitmap().let {b->File(diagnostic,"tab${tab}_$theme.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        for(tab in 0..4)capture(tab,"light")
        ui.onNodeWithTag("nav_3").performClick();ui.onNodeWithTag("v12_pin_grid").performClick();ui.onNodeWithContentDescription("Hearts 상세 열기").performClick()
        ui.onNodeWithTag("edit_heart").performClick();ui.onNodeWithTag("pin_note").performScrollTo().performTextReplacement("수정한 합성 메모")
        ui.onNodeWithTag("confirm_pin").performClick();ui.waitUntil(10000){repo.load().single().note=="수정한 합성 메모"}
        assertEquals(1,repo.load().size);assertNull(repo.load().single().place)
        val id=repo.load().single().id
        ui.onNodeWithContentDescription("Hearts 상세 열기").performClick();ui.onNodeWithText("기록 삭제").performClick();ui.waitUntil(10000){repo.load().isEmpty()}
        ui.onNodeWithTag("restore_heart_$id").performScrollTo().performClick();ui.waitUntil(10000){repo.load().size==1}
        assertEquals("수정한 합성 메모",repo.load().single().note)
        restoration.emulateSavedInstanceStateRestore();ui.onNodeWithTag("nav_3").assertIsSelected()
        ui.waitUntil(10000){ui.onAllNodesWithTag("hearts_search").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("hearts_search").performTextInput("합성 음악");ui.onNodeWithContentDescription("Hearts 상세 열기").assertExists()
        ui.onNodeWithTag("nav_4").performClick();ui.onNodeWithTag("dark_mode").performScrollTo().performClick()
        for(tab in 0..4)capture(tab,"dark")
        ui.onNodeWithTag("profile_music_preferences").performScrollTo().performClick();ui.onNodeWithTag("nav_1").assertIsSelected()
        val reopened=LocalArchiveRepository(File(root,"archive"));assertEquals(id,reopened.load().single().id)
        assertEquals(1,ArchiveProjection.onDate(HistorySnapshot(listOf(record)),reopened.load(),record.date!!).second.size)
        File(diagnostic,"result.json").writeText(org.json.JSONObject().put("source","SYNTHETIC_UI_REAL_LOCAL_REPOSITORY")
            .put("screens",5).put("themes",2).put("savedPins",1).put("location",org.json.JSONObject.NULL).put("editDeleteRestore",true).toString(2))
    }
}
