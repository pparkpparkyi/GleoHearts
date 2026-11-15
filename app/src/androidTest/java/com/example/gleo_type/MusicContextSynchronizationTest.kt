package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MusicContextSynchronizationTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun main(action: () -> Unit) = i.runOnMainSync(action)
    private fun await(name: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(name)
    }

    @Test fun actualPlaybackTrackerAndSeparateAggregates() {
        val c = (context.applicationContext as GleoApplication).container
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        c.musicContext.store.enabled = true
        var controller: MediaController? = null
        try {
            await("active collection") { c.driveSessions.currentDriveSessionId() != null }
            val drive = c.driveSessions.currentDriveSessionId()!!
            lateinit var future: ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context,
                ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            val player = future.get(30, TimeUnit.SECONDS)
            controller = player
            fun records() = context.filesDir.resolve("events").listFiles().orEmpty()
                .filter { it.name.startsWith(c.runId) && it.extension == "jsonl" }
                .flatMap { it.readLines(Charsets.UTF_8).map { row -> JSONObject(row) } }
                .filter { it.optString("driveSessionId") == drive }
            main {
                player.setMediaItems(listOf("jamendo_0010", "jamendo_0016", "jamendo_0020").map {
                    MediaItem.Builder().setMediaId(it).build() })
                player.prepare(); player.play()
            }
            await("started") { records().any { it.optJSONObject("event")?.optString("label") == "STARTED" } }
            val first = c.playbackSessions.currentId()!!
            val inject = InstrumentationRegistry.getArguments().getString("injectSpeed") == "true"
            if (inject) {
                val output = ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
                    "cmd car_service inject-continuous-events 291504647 10.0 -s 1 -d 12"
                )).use { it.readBytes().toString(Charsets.UTF_8) }
                assertFalse(output, output.contains("Exception"))
                await("injected speed reaches original JSONL and realtime during playback") {
                    records().any { it.optString("kind") == "speed_sample" && it.optString("rawSpeed") == "10.0" } &&
                        c.realtime.snapshot()?.entries?.any { it.value.speedKmh == 36.0 } == true
                }
            }
            Thread.sleep(3100)
            main { player.pause() }; Thread.sleep(1100)
            main { player.play() }; Thread.sleep(2100)
            assertEquals(first, c.playbackSessions.currentId())
            main { c.musicActions.mark("next_button", first); player.seekToNextMediaItem() }
            await("first aggregate") { c.musicContext.results().any { it.fields["playbackSessionId"] == first } }
            val second = c.playbackSessions.currentId()!!
            assertNotEquals(first, second)
            Thread.sleep(2100)
            var duration = 0L
            main { duration = player.duration }
            assertTrue(duration > 3000)
            // Actual seek-to-tail + natural automatic transition must never imply full listening.
            main { c.musicActions.mark("seek", second); player.seekTo(duration - 700) }
            await("automatic transition") { c.playbackSessions.currentId() != second }
            val third = c.playbackSessions.currentId()!!
            await("second aggregate") { c.musicContext.results().any { it.fields["playbackSessionId"] == second } }
            Thread.sleep(2100)
            main { c.stopCollection() }
            // Another test may leave a player item active when this collection begins.
            // Its explicitly incomplete aggregate is valid, but is outside these three new playback IDs.
            val targetIds = setOf(first, second, third)
            await("all three target aggregates persisted") {
                val file = context.filesDir.resolve("music-context-experiments/${c.runId}.jsonl")
                val stored = if (!file.isFile) emptySet() else file.readLines(Charsets.UTF_8).mapNotNull {
                    runCatching { JSONObject(it).optString("playbackSessionId") }.getOrNull()
                }.toSet()
                stored.containsAll(targetIds)
            }
            await("all three source summaries saved") {
                records().filter { it.optString("kind") == "listening_summary" }
                    .map { it.optString("playbackSessionId") }.toSet().containsAll(targetIds)
            }
            val result = c.musicContext.results().filter { it.fields["driveSessionId"] == drive &&
                it.fields["playbackSessionId"] in targetIds }
            assertEquals(3, result.size)
            result.forEach { value ->
                val f = value.fields
                assertEquals(c.runId, f["runId"])
                val source = records().single { it.optString("kind") == "listening_summary" &&
                    it.optString("playbackSessionId") == f["playbackSessionId"] }
                assertEquals(source.optString("uniqueListeningRate"), f["uniqueListeningRate"])
                assertEquals(source.optString("totalListeningWallMs"), f["creditedWallMs"])
                if (f["contextQuality"] != "available") assertNull(f["avgSpeedKmh"])
            }
            val tail = result.single { it.fields["playbackSessionId"] == second }.fields
            assertTrue((tail["uniqueListeningRate"] as String).toDouble() < 0.2)
            assertEquals("AUTO_TRANSITION", tail["endReason"])
            assertTrue(records().any { it.optString("kind") == "speed_sample" })
            assertTrue(records().any { it.optJSONObject("event")?.optString("label") == "SKIPPED" })
            val out = context.filesDir.resolve("diagnostics/music-context.json")
            out.parentFile!!.mkdirs()
            out.writeText(JSONObject().put("fingerprint", android.os.Build.FINGERPRINT)
                .put("runId", c.runId).put("driveSessionId", drive).put("result", "passed")
                .put("speedMode", if (inject) "car_service_injected_10mps" else "sdk_received_values")
                .put("rows", org.json.JSONArray(result.map { JSONObject(it.fields) }))
                .put("handoffDropped", c.musicContext.dropped.get())
                .put("processingFailures", c.musicContext.processingFailures.get())
                .put("storageFailures", c.musicContext.store.failures.get()).toString(2), Charsets.UTF_8)
        } finally {
            c.musicContext.store.enabled = false
            main { controller?.stop(); controller?.release(); activity.finish() }
        }
    }
}
