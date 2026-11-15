package com.example.gleo_type

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class GleoUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class TestPlayer : PlaybackPort {
        override val catalog = MutableStateFlow(listOf(Track("test_a", "UI Test A", "Synthetic"), Track("test_b", "UI Test B", "Synthetic"), Track("test_c", "UI Test C", "Synthetic")))
        override val state = MutableStateFlow(PlaybackUi(true, catalog.value.first(), duration = 100000))
        var nextCount = 0
        override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
        override fun play(track: Track) { state.value = state.value.copy(track = track, playing = true) }
        override fun next() { nextCount++; state.value = state.value.copy(track = catalog.value[1]) }
        override fun previous() { state.value = state.value.copy(track = catalog.value[0]) }
        override fun seek(position: Long) { state.value = state.value.copy(position = position) }
    }
    @Test fun gesturesNavigationEmptyCalendarPinsAndStateRestore() {
        val player = TestPlayer()
        val name = "ui_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val directory = File(context.cacheDir, name)
        val archive = LocalArchiveRepository(directory)
        val restore = StateRestorationTester(ui)
        restore.setContent { GleoScreen(player, object : DriveHistoryRepository {
            override fun refresh() = HistorySnapshot()
        }, archive, "local_synthetic_test", { null }, {}, {}, {}, name, ux = DrivingUx(true, false, 0, "synthetic_fixture"),
            recommendationModelAdapter = com.example.gleo_type.recommendation.RecommendationModelAdapter(
                File(directory,"isolated-model"),"SYNTHETIC_UI_ONLY")) }
        ui.onNodeWithText("속도 미수신").assertExists()
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "축소"))
        ui.onNodeWithTag("sheet_handle").performTouchInput { swipeUp() }
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "중간"))
        ui.onNodeWithTag("sheet_handle").performTouchInput { swipeUp() }
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "확장"))
        ui.onNodeWithTag("sheet_handle").performTouchInput { down(center); moveBy(Offset(0f, 20f)); cancel() }
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "확장"))
        ui.onNodeWithTag("sheet_handle").performTouchInput { swipeDown() }
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "중간"))
        ui.onNodeWithTag("nav_1").performClick()
        ui.onNodeWithTag("player_toggle").performClick(); ui.runOnIdle { assertTrue(player.state.value.playing) }
        ui.onNodeWithTag("player_toggle").performClick(); ui.runOnIdle { assertFalse(player.state.value.playing) }
        ui.onNodeWithTag("player_next").performClick(); ui.runOnIdle { assertEquals(1, player.nextCount) }
        ui.onNodeWithTag("player_previous").performClick()
        ui.onNodeWithTag("playback_seek").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        ui.runOnIdle { assertTrue(player.state.value.position > 0) }
        ui.onNodeWithTag("recommendation_pager").performTouchInput { swipeLeft() }
        ui.onNodeWithTag("recommendation_pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2/3"))
        ui.onNodeWithTag("recommendation_pager").performTouchInput { swipeRight() }
        ui.onNodeWithTag("recommendation_pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1/3"))
        ui.onNodeWithTag("recommendation_test_a").performTouchInput { down(center); moveBy(Offset(0f, -35f)); cancel() }
        ui.runOnIdle { assertTrue(prefs.getStringSet("excluded", emptySet())!!.isEmpty()) }
        ui.onNodeWithTag("recommendation_test_a").performTouchInput { down(center); moveBy(Offset(0f, -150f)); cancel() }
        ui.runOnIdle { assertTrue(prefs.getStringSet("excluded", emptySet())!!.isEmpty()) }
        ui.onNodeWithTag("recommendation_test_a").performTouchInput {
            swipe(Offset(width * .5f, height * .85f), Offset(width * .5f, height * .1f), 400)
        }
        ui.waitUntil(5000) { prefs.getStringSet("excluded", emptySet())!!.contains("test_a") }
        ui.onNodeWithText("복원").performClick()
        ui.waitUntil(5000) { prefs.getStringSet("excluded", emptySet())!!.isEmpty() }
        ui.onNodeWithTag("nav_2").performClick()
        ui.onNodeWithTag("calendar_next").performClick()
        ui.onNodeWithTag("calendar_day_15").performScrollTo().performClick()
        ui.onNodeWithText("이 날짜에는 기록이 없어요").assertExists()
        val selectedDate = prefs.getString("date", null)
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("nav_2").assertIsSelected()
        ui.runOnIdle { assertEquals(selectedDate, prefs.getString("date", null)) }
        ui.onNodeWithTag("nav_3").performClick()
        ui.onNodeWithText("아직 저장한 순간이 없어요").assertExists()
        ui.onNodeWithTag("create_pin").performClick()
        ui.onNodeWithText("위치 미확인").assertExists()
        ui.onNodeWithTag("pin_tags").performTextInput("ui-test, synthetic")
        ui.onNodeWithTag("pin_note").performTextInput("Synthetic UI persistence check")
        ui.runOnIdle { player.state.value = player.state.value.copy(track = player.catalog.value[1]) }
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("pin_note").assertTextContains("Synthetic UI persistence check")
        ui.onNodeWithTag("confirm_pin").performClick()
        ui.waitUntil(5000) { archive.load().size == 1 }
        ui.runOnIdle { assertNull(archive.load().single().place); assertEquals("test_a", archive.load().single().track?.id) }
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("아직 저장한 순간이 없어요").assertDoesNotExist()
        ui.onNodeWithText("열기 →").performClick()
        ui.onNodeWithText("기록 삭제").performClick()
        ui.waitUntil(5000) { archive.load().isEmpty() }
        ui.onNodeWithTag("nav_4").performClick()
        ui.onNodeWithTag("dark_mode").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("dark_mode").assertIsOff()
        ui.onNodeWithText("로컬 프로필 · 계정 미연결").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithTag("nav_0").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithTag("home_sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "축소"))
    }
}
