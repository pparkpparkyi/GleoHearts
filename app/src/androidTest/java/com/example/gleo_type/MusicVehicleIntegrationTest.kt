package com.example.gleo_type

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.example.gleo_type.music.MusicTestActivity
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs real ExoPlayer and the existing AAOS callback path. Emulator input is test data. */
@RunWith(AndroidJUnit4::class)
class MusicVehicleIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as GleoApplication).container
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }
    private fun rows(): List<JSONObject> = context.filesDir.resolve("events").listFiles()
        .orEmpty().filter { it.extension == "jsonl" }.flatMap { file ->
            file.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        }
    private fun label(row: JSONObject) = row.optJSONObject("event")?.optString("label")
    private fun tap(activity: Activity, text: String) = main {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == text) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        assertTrue("Missing button: $text", find(activity.window.decorView)?.performClick() == true)
    }

    @Test fun playbackSpeedPauseResumeSkipSeekAutoRepeatAndStop() {
        var controller: MediaController? = null
        var mainActivity: Activity? = null
        var musicActivity: Activity? = null
        try {
            mainActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("collection session") { container.driveSessions.currentDriveSessionId() != null }
            val drive = container.driveSessions.currentDriveSessionId()!!
            val musicScreen = instrumentation.startActivitySync(
                Intent(context, MusicTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            musicActivity = musicScreen
            lateinit var future: ListenableFuture<MediaController>
            main { future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            val player = future.get(30, TimeUnit.SECONDS)
            controller = player
            waitFor("playlist ready") {
                var ready = false
                main { ready = player.mediaItemCount >= 4 && player.playbackState == Player.STATE_READY }
                ready
            }
            fun records() = rows().filter { it.optString("driveSessionId") == drive }
            fun has(name: String) = records().any { label(it) == name }
            tap(musicScreen, "재생")
            waitFor("STARTED") { has("STARTED") }
            val first = records().first { label(it) == "STARTED" }.getString("playbackSessionId")

            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd car_service inject-continuous-events 291504647 10.0 -s 1 -d 5"
            )).use { it.readBytes() }
            waitFor("actual AAOS speed sample while playing") {
                records().any { it.optString("kind") == "speed_sample" &&
                    it.optString("rawSpeed") == "10.0" && it.optString("playbackSessionId") == first }
            }
            tap(musicScreen, "일시정지")
            waitFor("PAUSED") { has("PAUSED") }
            tap(musicScreen, "재생")
            waitFor("RESUMED") { has("RESUMED") }
            tap(musicScreen, "다음 곡")
            waitFor("manual skip and next STARTED") { has("SKIPPED") && records().count { label(it) == "STARTED" } >= 2 }
            val skip = records().first { label(it) == "SKIPPED" }
            assertEquals(first, skip.getString("playbackSessionId"))
            assertEquals("next_button", skip.getString("actionOrigin"))
            listOf("PAUSED", "RESUMED").forEach { name ->
                assertEquals(first, records().first { label(it) == name }.getString("playbackSessionId"))
            }
            val second = records().last { label(it) == "STARTED" }.getString("playbackSessionId")
            assertNotEquals(first, second)
            main { player.seekTo(2000) }
            waitFor("same-item seek") { records().any { label(it) == "SEEKED" && it.optString("playbackSessionId") == second } }
            // A controller seek without app UI evidence is deliberately unknown.
            main { player.seekToNextMediaItem() }
            waitFor("unknown transition") { has("UNKNOWN_TRANSITION") && records().count { label(it) == "STARTED" } >= 3 }
            main { assertTrue(player.duration > 1000); player.seekTo(player.duration - 400) }
            waitFor("natural auto transition after seek to tail") {
                records().any { label(it) == "AUTO_TRANSITION" && it.optString("transitionReason") == "auto" }
            }
            waitFor("next track ready") {
                var ready = false
                main { ready = player.isPlaying && player.duration > 1000 }
                ready
            }
            main { player.repeatMode = Player.REPEAT_MODE_ONE; player.seekTo(player.duration - 400) }
            waitFor("repeat occurrence") {
                records().any { label(it) == "AUTO_TRANSITION" && it.optString("transitionReason") == "repeat" }
            }
            waitFor("repeat STARTED") { records().count { label(it) == "STARTED" } >= 5 }
            val repeatEnd = records().first { label(it) == "AUTO_TRANSITION" && it.optString("transitionReason") == "repeat" }
            val repeatedStart = records().last { label(it) == "STARTED" }
            assertEquals(repeatEnd.getJSONObject("event").getJSONObject("music").getString("musicId"),
                repeatedStart.getJSONObject("event").getJSONObject("music").getString("musicId"))
            assertNotEquals(repeatEnd.getString("playbackSessionId"), repeatedStart.getString("playbackSessionId"))
            tap(musicScreen, "정지")
            waitFor("STOPPED") { has("STOPPED") }
            val stoppedId = records().last { label(it) == "STOPPED" }.getString("playbackSessionId")
            tap(musicScreen, "재생")
            waitFor("restart after stop") { records().count { label(it) == "STARTED" } >= 6 }
            assertNotEquals(stoppedId, records().last { label(it) == "STARTED" }.getString("playbackSessionId"))
            tap(musicScreen, "정지")
            waitFor("second STOPPED") { records().count { label(it) == "STOPPED" } >= 2 }
            val barrier = CountDownLatch(1)
            container.eventPublisher.submit({ barrier.countDown() }, { throw AssertionError(it) })
            assertTrue(barrier.await(10, TimeUnit.SECONDS))
            val music = records().filter { it.optJSONObject("event")?.optString("domain") == "MUSIC" }
            assertTrue(music.isNotEmpty())
            assertTrue(music.all { !it.isNull("receivedElapsedMs") && !it.isNull("playbackSessionId") &&
                it.getString("receivedAt") == it.getJSONObject("event").getString("timestamp") })
            assertEquals(1, music.count { label(it) == "SKIPPED" })
            val run = music.first().getString("runId")
            val ordered = rows().filter { it.optString("runId") == run }.sortedBy { it.getLong("sequence") }
            assertEquals((1L..ordered.size.toLong()).toList(), ordered.map { it.getLong("sequence") })
            val timedMusic = music.sortedBy { it.getLong("sequence") }.map { it.getLong("receivedElapsedMs") }
            assertTrue(timedMusic.zipWithNext().all { (a, b) -> b >= a })
        } finally {
            main { controller?.stop(); controller?.release(); musicActivity?.finish(); mainActivity?.finish() }
        }
    }
}
