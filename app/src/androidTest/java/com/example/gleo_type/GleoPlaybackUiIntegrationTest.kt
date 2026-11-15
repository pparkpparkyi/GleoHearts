package com.example.gleo_type

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.gleo_type.music.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GleoPlaybackUiIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val container get() = (ui.activity.application as GleoApplication).container
    private var originalPinIds: Set<String>? = null
    private var restartedPinIds: List<String> = emptyList()
    private val restartPhase get() = InstrumentationRegistry.getArguments().getString("uiRestartPhase")
    private val restartMarker get() = ui.activity.filesDir.resolve("diagnostics/ui/restart-check.json")
    private fun rows(drive: String) = ui.activity.filesDir.resolve("events").listFiles().orEmpty()
        .filter { it.extension == "jsonl" }.flatMap { f -> f.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }
        .filter { it.optString("driveSessionId") == drive }
    private fun label(r: JSONObject) = r.optJSONObject("event")?.optString("label")
    private fun capture(name: String) {
        ui.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = ui.activity.filesDir.resolve("diagnostics/ui").apply { mkdirs() }
        dir.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @After fun clearOnlyTestPlaybackQueue() {
        originalPinIds?.let { before ->
            val archive = com.example.gleo_type.ui.app.LocalArchiveRepository(ui.activity.filesDir.resolve("archive"))
            val created = archive.load().filter { it.id !in before && it.tags == listOf("ui-regression") && it.note == "Synthetic persistence check" }
            if (restartPhase == "prepare") {
                restartMarker.parentFile!!.mkdirs()
                restartMarker.writeText(JSONObject().put("ids", JSONArray(created.map { it.id }))
                    .put("pid", android.os.Process.myPid()).put("localUserId", container.localIdentity.id).toString(), Charsets.UTF_8)
            } else created.forEach { archive.delete(it.id) }
        }
        if (restartedPinIds.isNotEmpty()) {
            val archive = com.example.gleo_type.ui.app.LocalArchiveRepository(ui.activity.filesDir.resolve("archive"))
            restartedPinIds.forEach(archive::delete)
            restartMarker.delete()
        }
        lateinit var future: ListenableFuture<MediaController>
        ui.activityRule.scenario.onActivity { activity ->
            future = MediaController.Builder(activity, SessionToken(activity,
                ComponentName(activity, MusicPlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        ui.activityRule.scenario.onActivity { controller.stop(); controller.clearMediaItems(); controller.release() }
    }
    @Test fun newUiUsesRealMedia3AndPreservesMusicJournalAndSummary() {
        ui.waitUntil(30000) { container.driveSessions.currentDriveSessionId() != null }
        ui.waitUntil(30000) { ui.onAllNodes(hasTestTag("app_root") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "기록 로드됨")).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(30000) { ui.onAllNodesWithText("플랫폼 UX 제한 없음").fetchSemanticsNodes().isNotEmpty() }
        if (ui.onAllNodesWithText("닫기").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("닫기").performClick()
        if (restartPhase == "verify") {
            val marker = JSONObject(restartMarker.readText(Charsets.UTF_8))
            assertNotEquals(marker.getInt("pid"), android.os.Process.myPid())
            assertEquals(marker.getString("localUserId"), container.localIdentity.id)
            restartedPinIds = marker.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertTrue(restartedPinIds.isNotEmpty())
            val archive = com.example.gleo_type.ui.app.LocalArchiveRepository(ui.activity.filesDir.resolve("archive"))
            assertTrue(archive.load().map { it.id }.containsAll(restartedPinIds))
            ui.onNodeWithTag("nav_3").assertIsSelected()
            ui.waitUntil(10000) { ui.onAllNodesWithText("#ui-regression").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("#ui-regression").assertExists()
            return
        }
        val drive = container.driveSessions.currentDriveSessionId()!!
        ui.onNodeWithTag("nav_1").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("recommendation_play_jamendo_0010").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("player_toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("player_toggle").performClick()
        ui.waitUntil(30000) { container.musicState.currentSnapshot()?.isPlaying == true }
        ui.onNodeWithTag("player_toggle").performClick()
        ui.waitUntil(10000) { container.musicState.currentSnapshot()?.isPlaying == false }
        ui.onNodeWithTag("recommendation_play_jamendo_0010").performClick()
        ui.waitUntil(30000) { container.musicState.currentSnapshot()?.let { it.musicId == "jamendo_0010" && it.isPlaying && it.currentPositionMs >= 300 } == true }
        val playback = container.musicState.currentSnapshot()!!.playbackSessionId
        ui.onNodeWithTag("recommendation_jamendo_0010").performTouchInput {
            swipe(Offset(width * .5f, height * .85f), Offset(width * .5f, height * .1f), 400)
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText("복원").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("복원").performClick()
        val feedbackFile = ui.activity.filesDir.resolve("feedback/recommendation-feedback.jsonl")
        ui.waitUntil(10000) {
            feedbackFile.exists() && feedbackFile.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("driveSessionId") == drive && it.optString("musicId") == "jamendo_0010" }
                .map { it.optString("action") }.containsAll(listOf("recommendation_excluded", "recommendation_restored"))
        }
        ui.onNodeWithTag("player_toggle").performClick()
        ui.waitUntil(10000) { container.musicState.currentSnapshot()?.isPlaying == false }
        ui.onNodeWithTag("player_toggle").performClick()
        ui.waitUntil(10000) { rows(drive).any { label(it) == "RESUMED" && it.optString("playbackSessionId") == playback } }
        capture("music")
        ui.onNodeWithTag("playback_seek").performTouchInput { click(Offset(width * .25f, height * .5f)) }
        ui.waitUntil(10000) { rows(drive).any { label(it) == "SEEKED" } }
        ui.onNodeWithTag("player_next").performClick()
        ui.waitUntil(10000) { rows(drive).any { label(it) == "SKIPPED" && it.optString("playbackSessionId") == playback && it.optString("actionOrigin") == "next_button" } }
        ui.waitUntil(10000) { container.musicState.currentSnapshot()?.musicId != "jamendo_0010" }
        ui.onNodeWithTag("nav_0").performClick()
        // A preserved expanded sheet can cover the hero's collection button.
        repeat(2) { ui.onNodeWithTag("sheet_handle").performTouchInput { swipeDown() } }
        capture("home")
        ui.onNodeWithTag("collection_toggle").performClick()
        ui.waitUntil(15000) { rows(drive).any { it.optString("kind") == "session_stopped" } }
        ui.waitUntil(15000) { ui.onAllNodesWithText("주행을 기록했어요").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("주행을 기록했어요").assertExists()
        capture("drive-summary")
        val saved = rows(drive)
        assertTrue(saved.any { it.optString("kind") == "listening_summary" && it.optString("playbackSessionId") == playback })
        assertTrue(saved.filter { label(it) in listOf("STARTED", "PAUSED", "RESUMED", "SEEKED", "SKIPPED") }
            .all { it.optString("localUserId").startsWith("local_") })
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithTag("nav_3").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("nav_3").assertIsSelected()
        val archive = com.example.gleo_type.ui.app.LocalArchiveRepository(ui.activity.filesDir.resolve("archive"))
        val before = archive.load().map { it.id }.toSet(); originalPinIds = before
        ui.waitUntil(15000) { ui.onAllNodesWithTag("create_pin").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("create_pin").performClick()
        ui.onNodeWithTag("pin_tags").performTextInput("ui-regression")
        ui.onNodeWithTag("pin_note").performTextInput("Synthetic persistence check")
        ui.onNodeWithTag("confirm_pin").performClick()
        ui.waitUntil(10000) { archive.load().any { it.id !in before && "ui-regression" in it.tags } }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) { ui.onAllNodesWithText("#ui-regression").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("#ui-regression").assertExists()
    }
}
