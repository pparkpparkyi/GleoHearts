package com.example.gleo_type

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.JamendoResearchCatalog
import com.example.gleo_type.music.MusicPlaybackService
import com.example.gleo_type.music.MusicTestActivity
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class JamendoPlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as GleoApplication).container
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }
    private fun digestAsset(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(name).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun packagedFilesMatchVerifiedColabManifest() {
        val tracks = JamendoResearchCatalog.load(context)
        assertEquals(JamendoResearchCatalog.expectedIds, tracks.map { it.musicId })
        val catalog = JSONObject(context.assets.open("jamendo/catalog.json").bufferedReader().use { it.readText() })
        assertEquals(catalog.getString("manifestSha256"), digestAsset("jamendo/music_android_test_manifest.csv"))
        tracks.forEach { assertEquals(it.musicId, it.sha256, digestAsset("jamendo/${it.audioFile}")) }
    }

    private fun rows(drive: String): List<JSONObject> = context.filesDir.resolve("events").listFiles().orEmpty()
        .filter { it.extension == "jsonl" }.flatMap { file -> file.readLines(Charsets.UTF_8).mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        } }.filter { it.optString("driveSessionId") == drive }
    private fun label(row: JSONObject) = row.optJSONObject("event")?.optString("label")
    private fun musicId(row: JSONObject) = row.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")
    private fun tap(activity: Activity, title: String) {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == title) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        waitFor("UI button $title") {
            var clicked = false
            main { clicked = find(activity.window.decorView)?.performClick() == true }
            clicked
        }
    }

    @Test fun realThreeTrackPlaybackAndVehicleSession() {
        assertEquals(3, JamendoResearchCatalog.load(context).size)
        var mainActivity: Activity? = null
        var musicActivity: Activity? = null
        var controller: MediaController? = null
        try {
            mainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("drive session") { container.driveSessions.currentDriveSessionId() != null }
            val drive = container.driveSessions.currentDriveSessionId()!!
            val screen = instrumentation.startActivitySync(Intent(context, MusicTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            musicActivity = screen
            lateinit var future: ListenableFuture<MediaController>
            main { future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            val player = future.get(20, TimeUnit.SECONDS)
            controller = player
            main {
                // Previous test controllers may leave playWhenReady/repeat/speed set.
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
                player.setPlaybackSpeed(1f)
                player.setMediaItems(JamendoResearchCatalog.expectedIds.map { MediaItem.Builder().setMediaId(it).build() })
                player.prepare()
            }
            waitFor("Jamendo prepared") {
                var ready = false
                main { ready = player.currentMediaItem?.mediaId == "jamendo_0010" && player.playbackState == Player.STATE_READY }
                ready
            }
            fun has(name: String, id: String) = rows(drive).any { label(it) == name && musicId(it) == id }
            fun started(id: String) = rows(drive).first { label(it) == "STARTED" && musicId(it) == id }
            fun progressing() {
                waitFor("decoded audio position progress") {
                    var progressed = false
                    main { progressed = player.isPlaying && player.currentPosition >= 400 && player.duration > 1000 }
                    progressed
                }
            }
            tap(screen, "재생")
            waitFor("first STARTED") { has("STARTED", "jamendo_0010") }
            progressing()
            val firstSession = started("jamendo_0010").getString("playbackSessionId")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd car_service inject-continuous-events 291504647 10.0 -s 1 -d 5"
            )).use { it.readBytes() }
            waitFor("speed sample with Jamendo playback and shared drive") {
                rows(drive).any { it.optString("kind") == "speed_sample" && it.optString("rawSpeed") == "10.0" &&
                    it.optString("musicId") == "jamendo_0010" && it.optString("playbackSessionId") == firstSession }
            }
            tap(screen, "일시정지")
            waitFor("PAUSED") { has("PAUSED", "jamendo_0010") }
            tap(screen, "재생")
            waitFor("RESUMED") { has("RESUMED", "jamendo_0010") }
            tap(screen, "다음 곡")
            waitFor("SKIPPED and second STARTED") { has("SKIPPED", "jamendo_0010") && has("STARTED", "jamendo_0016") }
            progressing()
            val secondSession = started("jamendo_0016").getString("playbackSessionId")
            assertNotEquals(firstSession, secondSession)
            listOf("PAUSED", "RESUMED", "SKIPPED").forEach { name ->
                assertEquals(firstSession, rows(drive).first { label(it) == name && musicId(it) == "jamendo_0010" }
                    .getString("playbackSessionId"))
            }
            main { player.seekTo(player.duration - 500) }
            waitFor("second auto transition to third") {
                has("AUTO_TRANSITION", "jamendo_0016") && has("STARTED", "jamendo_0020")
            }
            progressing()
            val thirdSession = started("jamendo_0020").getString("playbackSessionId")
            assertEquals(3, setOf(firstSession, secondSession, thirdSession).size)
            val automatic = rows(drive).first { label(it) == "AUTO_TRANSITION" && musicId(it) == "jamendo_0016" }
            assertEquals("auto", automatic.getString("transitionReason"))
            assertEquals(secondSession, automatic.getString("playbackSessionId"))
            // Re-listen to the same range, then end collection while the Player keeps playing.
            Thread.sleep(1200)
            main { player.seekTo(0) }
            waitFor("third playing after rewind") { var playing = false; main { playing = player.isPlaying }; playing }
            Thread.sleep(1400)
            main { container.stopCollection() }
            waitFor("listening summaries flushed") {
                rows(drive).count { it.optString("kind") == "listening_summary" && it.optString("musicId") in JamendoResearchCatalog.expectedIds } == 3
            }
            val summaries = rows(drive).filter { it.optString("kind") == "listening_summary" && it.optString("musicId") in JamendoResearchCatalog.expectedIds }
            val badSide = summaries.single { it.optString("musicId") == "jamendo_0016" }
            assertTrue("Seek-to-tail must not imply full listening", badSide.getString("uniqueListeningRate").toDouble() < 0.1)
            val repeated = summaries.single { it.optString("musicId") == "jamendo_0020" }
            assertTrue(repeated.getString("totalListenedMediaMs").toDouble() > repeated.getString("uniqueListenedMediaMs").toDouble())
            assertEquals("true", repeated.getString("finalSnapshotAvailable"))
            assertEquals("collection_stopped", repeated.getString("closeReason"))
            assertTrue(rows(drive).all { it.optString("localUserId").startsWith("local_") && it.isNull("accountUserId") })
            main { assertTrue(player.isPlaying) }
            Thread.sleep(300)
            main { container.startCollection() }
            val nextDrive = container.driveSessions.currentDriveSessionId()!!
            assertNotEquals(drive, nextDrive)
            Thread.sleep(1100)
            tap(screen, "정지")
            waitFor("third STOPPED in next drive") { rows(nextDrive).any { label(it) == "STOPPED" && musicId(it) == "jamendo_0020" } }
            main { container.stopCollection() }
            val barrier = CountDownLatch(1)
            container.eventPublisher.submit({ barrier.countDown() }, { throw AssertionError(it) })
            assertTrue(barrier.await(10, TimeUnit.SECONDS))
            val evidence = rows(drive).filter { musicId(it) in JamendoResearchCatalog.expectedIds &&
                it.optJSONObject("event")?.optString("domain") == "MUSIC" }
            assertFalse(evidence.any { label(it) == "ERROR" })
            assertTrue(evidence.all { !it.isNull("playbackSessionId") && !it.isNull("receivedElapsedMs") })
            val tracks = JSONArray()
            JamendoResearchCatalog.expectedIds.forEach { id ->
                val start = started(id)
                val music = start.getJSONObject("event").getJSONObject("music")
                assertTrue(music.getString("durationMs").toLong() > 0)
                tracks.put(JSONObject().put("musicId", id).put("playbackSessionId", start.getString("playbackSessionId"))
                    .put("durationMs", music.getString("durationMs")).put("timestamp", start.getJSONObject("event").getString("timestamp")))
            }
            val counts = JSONObject()
            evidence.groupingBy { label(it)!! }.eachCount().forEach { (name, count) -> counts.put(name, count) }
            val report = JSONObject().put("driveSessionId", drive).put("runId", evidence.first().getString("runId"))
                .put("nextDriveSessionId", nextDrive).put("listeningSummaries", JSONArray(summaries))
                .put("result", "passed").put("tracks", tracks).put("musicCounts", counts)
                .put("automaticTransitionMethod", "seek_near_end_then_natural_transition")
                .put("speedSamples", rows(drive).count { it.optString("kind") == "speed_sample" })
            val directory = context.filesDir.resolve("diagnostics").apply { mkdirs() }
            FileOutputStream(directory.resolve("jamendo-playback-report.json")).use {
                it.write(report.toString(2).toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
        } finally {
            main { controller?.stop(); controller?.release(); musicActivity?.finish(); mainActivity?.finish() }
        }
    }
}
