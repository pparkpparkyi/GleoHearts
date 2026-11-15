package com.example.gleo_type

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DrivingUxUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun unknownAndRestrictedStatesKeepAudioButBlockComplexUiAndRetainDraft() {
        val ux = MutableStateFlow(DrivingUx())
        val pins = mutableListOf<Pin>()
        val player = object : PlaybackPort {
            override val catalog = MutableStateFlow(listOf(Track("synthetic", "Synthetic track", "Test artist")))
            override val state = MutableStateFlow(PlaybackUi(true, catalog.value[0], duration = 100000))
            override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
            override fun play(track: Track) { state.value = state.value.copy(playing = true) }
            override fun next() = Unit
            override fun previous() = Unit
            override fun seek(position: Long) = Unit
        }
        val archive = object : ArchiveRepository {
            override fun load() = synchronized(pins) { pins.toList() }
            override fun save(pin: Pin) { synchronized(pins) { pins.add(pin) } }
            override fun delete(id: String) { synchronized(pins) { pins.removeAll { it.id == id } } }
        }
        val history = object : DriveHistoryRepository { override fun refresh() = HistorySnapshot() }
        val preferenceName = "ux-test-${UUID.randomUUID()}"
        ui.setContent { val policy by ux.collectAsState(); GleoScreen(player, history, archive, "synthetic", { null }, {}, {}, {}, preferenceName, ux = policy) }
        ui.onNodeWithTag("nav_1").performClick()
        ui.onNodeWithTag("player_toggle").performClick()
        ui.runOnIdle { assertTrue(player.state.value.playing) }
        ui.onNodeWithTag("playback_seek").assertDoesNotExist()
        ui.onNodeWithTag("recommendation_pager").assertDoesNotExist()
        ui.onNodeWithTag("nav_4").performClick()
        ui.onNodeWithTag("dark_mode").assertDoesNotExist()
        ui.runOnIdle { ux.value = DrivingUx(true,false,0,"synthetic_fixture") }
        ui.onNodeWithTag("nav_3").performClick()
        ui.onNodeWithTag("create_pin").performClick()
        ui.onNodeWithTag("pin_note").performTextInput("Draft preserved through restriction")
        ui.runOnIdle { ux.value = DrivingUx(true,true,72,"synthetic_fixture") }
        ui.onNodeWithTag("pin_note").assertDoesNotExist()
        ui.onNodeWithTag("confirm_pin").assertDoesNotExist()
        ui.runOnIdle { assertTrue(player.state.value.playing); assertTrue(pins.isEmpty()) }
        ui.runOnIdle { ux.value = DrivingUx(true,false,0,"synthetic_fixture") }
        ui.onNodeWithTag("pin_note").assertTextContains("Draft preserved through restriction")
        ui.onNodeWithTag("confirm_pin").performClick()
        ui.waitUntil(10000) { synchronized(pins) { pins.size == 1 } }
        ui.runOnIdle { assertNull(pins.single().place) }
    }
}
