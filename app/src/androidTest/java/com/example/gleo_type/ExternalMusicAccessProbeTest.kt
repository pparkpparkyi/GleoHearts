package com.example.gleo_type

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicTestActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Diagnostic only: no shell identity, permission grants, listener service, or external controls. */
@RunWith(AndroidJUnit4::class)
class ExternalMusicAccessProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun JSONObject.value(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)

    private fun snapshot(controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val state = controller.playbackState
        val elapsed = SystemClock.elapsedRealtime()
        val estimated = state?.let {
            if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0)
                it.position + ((elapsed - it.lastPositionUpdateTime).coerceAtLeast(0) * it.playbackSpeed).toLong()
            else it.position
        }
        return JSONObject().value("sourceApp", controller.packageName)
            .value("receivedAt", Instant.now().toString()).value("receivedElapsedMs", elapsed)
            .value("originalTrackId", metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            .value("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            .value("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .value("durationMs", metadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION))
            .value("reportedPositionMs", state?.position).value("estimatedPositionMs", estimated)
            .value("positionBasis", "platform_state_plus_elapsed_estimate")
            .value("playbackState", state?.state)
            .value("isPlaying", state?.let { it.state == PlaybackState.STATE_PLAYING })
            .value("stateUpdatedElapsedMs", state?.lastPositionUpdateTime)
            .value("actions", state?.actions).value("errorMessage", state?.errorMessage?.toString())
            .value("hasMetadata", metadata != null).value("transitionCause", "unknown")
    }

    private data class Connection(val browser: MediaBrowser?, val controller: MediaController?, val result: JSONObject)
    private fun connect(component: ComponentName, browseRoot: Boolean = true): Connection {
        val done = CountDownLatch(1)
        val result = JSONObject().value("component", component.flattenToString())
            .value("sourceApp", component.packageName).value("controlAttempted", false)
        var browser: MediaBrowser? = null
        var controller: MediaController? = null
        main {
            try {
                browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            controller = MediaController(context, browser!!.sessionToken)
                            result.value("connection", "connected").value("root", browser!!.root)
                                .value("snapshot", snapshot(controller!!))
                        } catch (error: Exception) {
                            result.value("connection", "token_error").value("error", error.toString())
                        }
                        done.countDown()
                    }
                    override fun onConnectionFailed() {
                        result.value("connection", "rejected"); done.countDown()
                    }
                    override fun onConnectionSuspended() {
                        result.value("connection", "suspended"); done.countDown()
                    }
                }, null)
                browser!!.connect()
            } catch (error: Exception) {
                result.value("connection", "exception").value("error", error.toString()); done.countDown()
            }
        }
        if (!done.await(6, TimeUnit.SECONDS)) result.value("connection", "timeout")
        if (controller != null && browseRoot) {
            val loaded = CountDownLatch(1)
            main {
                browser!!.subscribe(browser!!.root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        val items = JSONArray()
                        children.take(20).forEach { item -> items.put(JSONObject()
                            .value("originalTrackId", item.mediaId).value("title", item.description.title?.toString())
                            .value("subtitle", item.description.subtitle?.toString())
                            .value("playable", item.isPlayable).value("browsable", item.isBrowsable)) }
                        result.value("rootChildren", items).value("rootChildCount", children.size)
                        loaded.countDown()
                    }
                    override fun onError(parentId: String) {
                        result.value("browseError", "root_subscription_error"); loaded.countDown()
                    }
                })
            }
            if (!loaded.await(4, TimeUnit.SECONDS)) result.value("browseError", "root_subscription_timeout")
        }
        return Connection(browser, controller, result)
    }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }

    /** Run after the user starts the target app. Never sends a transport command. */
    @Test fun probeConfiguredExternalServiceReadOnly() {
        val args = InstrumentationRegistry.getArguments()
        val packageName = args.getString("readOnlyPackage")
        val serviceName = args.getString("readOnlyService")
        org.junit.Assume.assumeTrue("Explicit external service required", packageName != null && serviceName != null)
        require(packageName != context.packageName) { "Use the own-app regression test for the local player" }
        val watchMs = (args.getString("watchMs")?.toLongOrNull() ?: 5000).coerceIn(0, 30000)
        val report = JSONObject().value("probeId", args.getString("probeId"))
            .value("observedAt", Instant.now().toString()).value("processUid", Process.myUid())
            .value("externalControlsAttempted", false).value("transitionCause", "unknown")
        val observations = mutableListOf<JSONObject>()
        val connection = connect(ComponentName(packageName!!, serviceName!!))
        var callback: MediaController.Callback? = null
        try {
            connection.controller?.let { player ->
                main {
                    callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            observations += snapshot(player).value("callback", "metadata_changed")
                        }
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            observations += snapshot(player).value("callback", "playback_state_changed")
                        }
                    }
                    player.registerCallback(callback!!, Handler(Looper.getMainLooper()))
                    observations += snapshot(player).value("callback", "initial_snapshot")
                }
                CountDownLatch(1).await(watchMs, TimeUnit.MILLISECONDS)
                main { observations += snapshot(player).value("callback", "final_snapshot") }
            }
        } finally {
            main {
                callback?.let { connection.controller?.unregisterCallback(it) }
                connection.browser?.disconnect()
            }
            report.value("service", connection.result).value("observations", JSONArray(observations))
                .value("trackMetadataObserved", observations.any {
                    it.optBoolean("hasMetadata") && !it.isNull("title") && it.optString("title").isNotBlank()
                })
            val directory = context.filesDir.resolve("diagnostics").apply { mkdirs() }
            FileOutputStream(directory.resolve("read-only-external-probe.json")).use { stream ->
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
        }
    }

    @Test fun inspectAccessAndOwnPlaybackBaseline() {
        val report = JSONObject().value("observedAt", Instant.now().toString())
            .value("targetPackage", context.packageName).value("processUid", Process.myUid())
            .value("shellIdentityAdopted", false).value("externalControlsAttempted", false)
            .value("mediaContentControlGranted", context.checkSelfPermission("android.permission.MEDIA_CONTENT_CONTROL") == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(MediaSessionManager::class.java)
        try {
            report.value("activeSessionsApi", "allowed").value("activeSessionPackages",
                JSONArray(manager.getActiveSessions(null).map { it.packageName }))
        } catch (error: SecurityException) {
            report.value("activeSessionsApi", "SecurityException").value("activeSessionsError", error.message)
        }
        report.value("visibleBrowserServices", JSONArray(context.packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService"), 0
        ).map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }))
        val external = JSONArray()
        val components = listOf(
            ComponentName("com.android.car.media.localmediaplayer", "com.android.car.media.localmediaplayer.LocalMediaBrowserService"),
            ComponentName("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"),
            ComponentName("com.android.car.radio", "com.android.car.radio.service.RadioAppService"),
            ComponentName("com.google.android.carassistant", "com.google.android.libraries.assistant.auto.tng.media.mediabrowser.NewsMediaBrowserService")
        )
        components.forEach { component ->
            val connection = connect(component)
            external.put(connection.result)
            main { connection.browser?.disconnect() }
        }
        report.value("externalServices", external)
        fun saveReport() {
            val directory = context.filesDir.resolve("diagnostics").apply { mkdirs() }
            FileOutputStream(directory.resolve("external-music-probe.json")).use { stream ->
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
        }
        saveReport()
        var activity: Activity? = null
        var own: Connection? = null
        var callback: MediaController.Callback? = null
        val observations = mutableListOf<JSONObject>()
        try {
            activity = instrumentation.startActivitySync(Intent(context, MusicTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            own = connect(ComponentName(context.packageName, "${context.packageName}.music.MusicPlaybackService"))
            assertEquals(8 + com.example.gleo_type.music.JamendoResearchCatalog.load(context).size,
                own.result.getInt("rootChildCount"))
            val children = own.result.getJSONArray("rootChildren")
            for (i in 0 until children.length()) {
                assertTrue(children.getJSONObject(i).getBoolean("playable"))
                assertFalse(children.getJSONObject(i).getBoolean("browsable"))
            }
            val player = own.controller ?: error("Own MediaBrowser baseline failed")
            main {
                callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        observations += snapshot(player).value("callback", "metadata_changed")
                    }
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        observations += snapshot(player).value("callback", "playback_state_changed")
                    }
                }
                player.registerCallback(callback!!, Handler(Looper.getMainLooper()))
            }
            waitFor("own prepared metadata") { player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.let { it > 0 } == true }
            main { player.transportControls.play() }
            waitFor("own playing") { player.playbackState?.state == PlaybackState.STATE_PLAYING }
            waitFor("own position progressed") { snapshot(player).optLong("estimatedPositionMs") >= 500 }
            main { observations += snapshot(player).value("callback", "playing_sample") }
            val firstId = player.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            main { player.transportControls.skipToNext() }
            waitFor("own track changed") { player.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.let { it != firstId } == true }
            waitFor("next prepared") { player.playbackState?.state == PlaybackState.STATE_PLAYING }
            main { observations += snapshot(player).value("callback", "next_track_sample"); player.transportControls.pause() }
            waitFor("own paused") { player.playbackState?.state == PlaybackState.STATE_PAUSED }
            main { observations += snapshot(player).value("callback", "paused_sample") }
            own.result.value("controlAttempted", true).value("controlsVerified", JSONArray(listOf("play", "next", "pause")))
            assertTrue(observations.any { !it.isNull("title") && !it.isNull("artist") && it.optLong("durationMs") > 0 })
        } finally {
            main {
                own?.controller?.let { player ->
                    callback?.let { player.unregisterCallback(it) }
                    player.transportControls.stop()
                }
                own?.browser?.disconnect()
                activity?.finish()
            }
            report.value("ownService", own?.result).value("ownObservations", JSONArray(observations))
                .value("driveSessionId", (context.applicationContext as GleoApplication).container.driveSessions.currentDriveSessionId())
            saveReport()
        }
    }
}
