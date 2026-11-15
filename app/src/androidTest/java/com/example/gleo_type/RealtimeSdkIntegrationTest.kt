package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.example.gleo_type.vehicle.AAOSVehicleMonitor
import com.example.gleo_type.realtime.*
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealtimeSdkIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }

    @Test fun actualPlaybackSpeedAndBuffer() {
        val report = JSONObject().put("environment", android.os.Build.FINGERPRINT)
        val destination = context.filesDir.resolve("diagnostics/realtime-sdk.json")
        destination.parentFile!!.mkdirs()
        var activity: android.app.Activity? = null
        var controller: MediaController? = null
        val rawCallbacks = AtomicInteger()
        val rawTen = AtomicInteger()
        val probe = AAOSVehicleMonitor(context, onSpeedSample = { speed, _, _ ->
            rawCallbacks.incrementAndGet(); if (speed == 10f) rawTen.incrementAndGet()
        }, onEvent = {})
        val container = (context.applicationContext as GleoApplication).container
        val inject = InstrumentationRegistry.getArguments().getString("injectSpeed") != "false"
        report.put("speedMode", if (inject) "car_service_injected_10mps" else "sdk_received_values")
        fun records() = context.filesDir.resolve("events").listFiles().orEmpty()
            .filter { it.name.startsWith(container.runId) && it.extension == "jsonl" }
            .flatMap { f -> f.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }
        try {
            report.put("carService", shell("service check car_service"))
            report.put("permission", context.checkSelfPermission("android.car.permission.CAR_SPEED"))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("collection start") { container.driveSessions.currentDriveSessionId() != null }
            val drive = container.driveSessions.currentDriveSessionId()!!
            report.put("runId", container.runId).put("driveSessionId", drive)
            probe.startMonitoring()
            lateinit var future: ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context,
                ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            val player = future.get(30, TimeUnit.SECONDS)
            controller = player
            main {
                player.setMediaItems(listOf("jamendo_0010", "jamendo_0016").map { MediaItem.Builder().setMediaId(it).build() })
                player.prepare(); player.play()
            }
            await("STARTED") { records().any { it.optJSONObject("event")?.optString("label") == "STARTED" } }
            val first = container.playbackSessions.currentId()
            await("callback registration ready") { rawCallbacks.get() > 0 && container.realtime.diagnostics().speedAccepted > 0 }
            if (inject) {
                // Continuous CarShell injection adds duration seconds to source time. Use current-time events.
                report.put("injectionOutput", "10_single_current_timestamp_events")
                val began = SystemClock.elapsedRealtime()
                repeat(10) { tick ->
                    shell("cmd car_service inject-vhal-event 291504647 10.0 -t 0")
                    await("scheduled input cadence") { SystemClock.elapsedRealtime() >= began + (tick + 1) * 1050L }
                }
                await("raw AAOS 10 m/s callback") { rawTen.get() > 0 }
            }
            await("filtered sample / JSONL / realtime") {
                records().any { it.optString("kind") == "speed_sample" && (!inject || it.optString("rawSpeed") == "10.0") && it.optString("driveSessionId") == drive } &&
                    container.realtime.snapshot()?.entries?.any { it.value.kind == ObservationKind.SPEED && (!inject || it.value.speedKmh == 36.0) } == true
            }
            assertEquals(0, report.getInt("permission"))
            main { player.pause() }
            await("PAUSED") { records().any { it.optJSONObject("event")?.optString("label") == "PAUSED" } }
            main { player.play() }
            await("RESUMED") { records().any { it.optJSONObject("event")?.optString("label") == "RESUMED" } }
            assertEquals(first, container.playbackSessions.currentId())
            main {
                container.musicActions.mark("next_button", first)
                player.seekToNextMediaItem()
            }
            await("SKIPPED") { records().any { it.optJSONObject("event")?.optString("label") == "SKIPPED" } }
            assertNotEquals(first, container.playbackSessions.currentId())
            assertEquals(container.runId, container.realtime.snapshot()!!.session.runId)
            assertEquals(drive, container.realtime.snapshot()!!.session.driveSessionId)
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            val key = RealtimeSession("synthetic-load", "memory")
            val buffer = RealtimeEventBuffer(key, { SystemClock.elapsedRealtime() })
            repeat(20000) { buffer.offer(RealtimeObservation(key, ObservationKind.SPEED,
                SystemClock.elapsedRealtime(), speedKmh = 36.0)) }
            repeat(2000) { index -> container.realtime.offer(RealtimeObservation(
                RealtimeSession(container.runId, drive), ObservationKind.OTHER, SystemClock.elapsedRealtime(),
                eventId = "synthetic-load-$index", label = "SYNTHETIC_LOAD")) }
            var stillPlaying = false
            main { stillPlaying = player.isPlaying }
            assertTrue("Media3 continues under synthetic buffer saturation", stillPlaying)
            assertTrue(container.realtime.snapshot()!!.diagnostics.sampleOverflow > 0)
            assertNull("Saturated input must remain unknown to recommendation consumers", container.realtime.usableContext())
            val after = Debug.MemoryInfo(); Debug.getMemoryInfo(after)
            report.put("pssBeforeKiB", info.totalPss).put("pssAfterKiB", after.totalPss)
                .put("retainedLoadEntries", buffer.snapshot().entries.size)
            buffer.close()
            main { container.stopCollection() }
            await("session stopped on disk") { records().any { it.optString("kind") == "session_stopped" && it.optString("driveSessionId") == drive } }
            assertNull(container.realtime.snapshot()); assertNull(container.realtime.usableContext())
            assertFalse(container.realtime.lastClosedDiagnostics()!!.missingStop)
            report.put("result", "passed")
        } finally {
            report.put("rawCallbacks", rawCallbacks.get()).put("rawTenCallbacks", rawTen.get())
                .put("ingress", container.realtime.diagnostics().toString())
                .put("closedBufferDiagnostics", container.realtime.lastClosedDiagnostics()?.toString())
            destination.writeText(report.toString(2), Charsets.UTF_8)
            probe.release()
            main { controller?.stop(); controller?.clearMediaItems(); controller?.release(); activity?.finish() }
        }
    }
}
