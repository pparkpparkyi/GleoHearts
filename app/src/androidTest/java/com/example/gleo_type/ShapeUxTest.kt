package com.example.gleo_type

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ShapeUxTest {
    @get:Rule val ui = createComposeRule()
    private class Player : PlaybackPort {
        override val state = MutableStateFlow(PlaybackUi(connected = true, catalogReady = false))
        override val catalog = MutableStateFlow<List<Track>>(emptyList())
        var commands = 0
        override fun toggle() { commands++ }; override fun play(track: Track) { commands++ }
        override fun next() { commands++ }; override fun previous() { commands++ }; override fun seek(position: Long) { commands++ }
    }
    @Test fun honestStatesRegenerationHistoryAndReasonsDoNotCommandPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "shape_${UUID.randomUUID()}"; val player = Player(); val runs = MemoryRecommendationHistory()
        ui.setContent { GleoScreen(player, object : DriveHistoryRepository { override fun refresh() = HistorySnapshot() },
            LocalArchiveRepository(File(context.cacheDir, name)), "synthetic", { "synthetic-drive" }, {}, {}, {}, name,
            DrivingUx(true, false, 0, "synthetic_fixture"), recommendationHistory = runs) }
        ui.onNodeWithTag("nav_1").performClick()
        ui.onNodeWithTag("recommendations_loading").assertExists()
        ui.runOnIdle { player.state.value = player.state.value.copy(catalogReady = true, catalogError = "synthetic error") }
        ui.onNodeWithTag("recommendations_error").assertExists()
        ui.runOnIdle { player.state.value = player.state.value.copy(catalogError = null) }
        ui.onNodeWithTag("recommendations_empty").assertExists()
        ui.runOnIdle { player.catalog.value = (1..3).map { Track("test_$it", "곡 $it", "테스트") } }
        ui.waitUntil(5000) { runs.load().size == 1 }
        val first = runs.load().first()
        ui.onNodeWithTag("regenerate_recommendations").performScrollTo().performClick()
        ui.waitUntil(5000) { runs.load().size == 2 }
        assertNotEquals(first.musicIds, runs.load().last().musicIds)
        assertEquals(0, player.commands)
        ui.onNodeWithTag("recommendation_sources").performScrollTo().performClick()
        ui.onNodeWithText("AI 설명이 아닙니다 · 차량 맥락·음향 특징 미사용").assertExists()
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithTag("recommendation_history").performScrollTo().performClick()
        ui.onNodeWithTag("restore_run_${first.id}").performScrollTo().performClick()
        ui.onNodeWithTag("recommendation_feedback").assertTextContains("이전 목록을 복원", substring = true)
        assertEquals(2, runs.load().size); assertEquals(0, player.commands)
        ui.onNodeWithTag("suggestion_SAVED").performScrollTo().performClick()
        ui.waitUntil(5000) { runs.load().size == 3 }
        ui.onNodeWithTag("recommendations_empty").assertExists()
    }
}
