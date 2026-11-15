package com.example.gleo_type

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.event.vehicle.VehicleEventSource
import com.example.gleo_type.music.RegistryPlaybackTestService
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual emulator Car callbacks, production pipelines, isolated stores and explicit TEST preferences.
 * Does not grant permissions, start navigation, operate a physical vehicle, or change a user profile.
 */
class Step11SdkContextIntegrationTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun main(f: () -> Unit) = i.runOnMainSync(f)
    private fun shell(s: String) = ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(s))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(label: String, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) { if (f()) return; Thread.sleep(100) }
        fail(label)
    }

    @Test fun actualSdkSnapshotRankingRebindAndPlayback() {
        assertTrue("Only an automotive emulator, not a physical car", context.packageManager.hasSystemFeature("android.hardware.type.automotive"))
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("emulator", true))
        val report = JSONObject().put("input", "EMULATOR_VHAL_THROUGH_REAL_AAOS_CALLBACK")
            .put("preference", "EXPLICIT_TEST_POP_HAPPY_NOT_USER_PROFILE").put("physicalVehicle", false)
        val proof = File(context.cacheDir, "step11-sdk-context-${UUID.randomUUID()}.json")
        val registry = File(context.cacheDir, "audio-registry-approved-samples")
        RegistryPlaybackTestService.testRoot = registry
        val foreground = ActivityScenario.launch(CarV10TestActivity::class.java)
        lateinit var future: ListenableFuture<MediaBrowser>
        main { future = MediaBrowser.Builder(context, SessionToken(context,
            ComponentName(context, RegistryPlaybackTestService::class.java))).buildAsync() }
        val player = future.get(30, TimeUnit.SECONDS)
        val c = RegistryPlaybackTestService.activeContainer!!
        val fixture = File(context.cacheDir, "step11-model-${UUID.randomUUID()}").apply { mkdirs() }
        val link = File(fixture, "models/clap500").apply { mkdirs() }
        listOf("manifest.json", "embeddings.bin").forEach { name ->
            android.system.Os.symlink(File(context.filesDir, "models/clap500/$name").absolutePath, File(link, name).absolutePath)
        }
        val runtime = OperationalRecommendationRuntime(fixture, c.localIdentity.id,
            { context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use { it.readText() } },
            { context.assets.open("audio-assets/catalog-v1.json").bufferedReader().use { it.readText() } })
        val adapter = RecommendationModelAdapter(fixture, c.localIdentity.id, PlaybackMappingVerifier(context)::verify,
            contextSnapshotProvider = { c.contextSnapshots.capture(it) }, contextRunId = { c.runId }, operationalRuntime = runtime)
        val callbacks = AtomicInteger()
        val owner = "step11-isolated-owner"
        c.drivingContext.attach(owner)
        val source = VehicleEventSource({ error("Typed SDK intentionally not started without authorization") },
            c.eventFactory, c.musicState, c.eventPublisher)
        val monitor = AAOSVehicleMonitor(context,
            onSpeedSample = { speed, elapsed, vehicle -> callbacks.incrementAndGet(); c.speedSamples.accept(speed, elapsed, vehicle) },
            onConnectionState = { c.drivingContext.connection(owner, it) },
            onEvent = { dto -> if (dto.apiName == "PERF_VEHICLE_SPEED")
                source.onAaosSpeed((dto.values.getOrNull(1)?.value as? Number)?.toFloat()) })
        lateinit var children: ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
        main { children = player.getChildren("gleo_root", 0, 100, null) }
        val catalog = children.get(30, TimeUnit.SECONDS).value!!.map {
            Track(it.mediaId, it.mediaMetadata.title.toString(), it.mediaMetadata.artist.toString()) }
        report.put("actualBrowsedCatalogCount", catalog.size)
        fun rows() = File(registry, "journal/events").listFiles().orEmpty()
            .filter { it.name.startsWith(c.runId) }.flatMap { file -> file.readLines(Charsets.UTF_8)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }
        val decisions = JSONArray()
        try {
            assertTrue(adapter.load())
            main { c.startCollection(); player.volume = 0f }
            val drive = c.driveSessions.currentDriveSessionId()!!
            fun recommend(label: String): RecommendationRun {
                val snapshot = c.contextSnapshots.capture(drive)
                val run = adapter.recommend(catalog, MusicScope.ALL, emptySet(), emptySet(), 0, null, drive, snapshot = snapshot)
                decisions.put(JSONObject().put("stage", label).put("origin", run.origin.name)
                    .put("quality", snapshot.drivingInput.quality).put("fallback", snapshot.drivingInput.fallbackReason)
                    .put("averageSpeedKmh", snapshot.drivingInput.averageSpeedKmh).put("source", snapshot.drivingInput.source.name)
                    .put("coverage", snapshot.drivingInput.coverage).put("observedDurationMs", snapshot.drivingInput.observedDurationMs)
                    .put("musicIds", JSONArray(run.musicIds)).put("scores", JSONObject(run.scores))
                    .put("evidence", run.contextEvidence?.let(::JSONObject)))
                if (run.origin == RecommendationOrigin.AI_MODEL) {
                    val model = RecommendationModelAdapter::class.java.getDeclaredField("model").apply { isAccessible = true }
                        .get(adapter) as MusicEmbeddingRepository
                    val full = ContextFusionRanker(model, runtime.runtime()!!.encoder).rank(c.localIdentity.id,
                        model.onboarding(setOf("pop"), setOf("happy")), snapshot, SystemClock.elapsedRealtime(),
                        System.currentTimeMillis(), null, topK = 500)
                    decisions.getJSONObject(decisions.length() - 1)
                        .put("fullModelScoredCount", full.scores.size)
                        .put("fullModelTop10", JSONArray(full.scores.take(10).map { JSONObject()
                            .put("musicId", it.musicId).put("score", it.total).put("parts", JSONObject(it.contributions)) }))
                        .put("fullModelDrivingAffectedCount", full.scores.count { (it.contributions["driving"] ?: 0.0) != 0.0 })
                }
                return run
            }
            assertEquals(RecommendationOrigin.TEST_CATALOG, recommend("no_user_test_profile_yet").origin)
            adapter.initialize(setOf("pop"), setOf("happy")) // isolated, explicitly synthetic preference only
            assertEquals(RecommendationOrigin.AI_MODEL, recommend("preference_only_before_connection").origin)
            monitor.startMonitoring()
            await("actual AAOS subscriptions") { monitor.connectionState().state == CarConnectionState.CONNECTED }
            for (speed in listOf(0, 3, 20)) {
                val before = callbacks.get()
                val injectionStarted = SystemClock.elapsedRealtime()
                // Continuous CarShell injection deliberately timestamps events duration seconds ahead.
                // Use current-timestamp single injections; never subtract that offset in the app.
                // Five-Hz test inputs avoid phase-lock to the emulator's native zero updates.
                // Production callback registration and 1-second persistence filter stay unchanged.
                report.put("injection$speed", "single_vhal_current_timestamp_5hz_until_3_persisted_min12_max30_seconds")
                var tick = 0
                // Native zero VHAL callbacks compete with injected callbacks for the production 1Hz gate.
                // Keep supplying input while awaiting evidence; do not wait 45s after stopping all input.
                while (SystemClock.elapsedRealtime() - injectionStarted < 30_000 &&
                    (SystemClock.elapsedRealtime() - injectionStarted < 12_000 ||
                        rows().count { it.optString("kind") == "speed_sample" && it.optString("rawSpeed") == "$speed.0" } < 3)) {
                    shell("cmd car_service inject-vhal-event 291504647 $speed.0 -t 0")
                    val next = injectionStarted + (++tick) * 200L
                    await("scheduled emulator input cadence") { SystemClock.elapsedRealtime() >= next }
                }
                report.put("injection${speed}DurationMs", SystemClock.elapsedRealtime() - injectionStarted)
                report.put("injection${speed}Persisted", rows().count { it.optString("kind") == "speed_sample" && it.optString("rawSpeed") == "$speed.0" })
                await("actual raw callback and stored ${speed}m/s") { callbacks.get() > before &&
                    SystemClock.elapsedRealtime() - injectionStarted >= 12_000 &&
                    rows().count { it.optString("kind") == "speed_sample" && it.optString("rawSpeed") == "$speed.0" } >= 3 }
                await("fresh usable context") { c.drivingContext.current(drive).quality == "available" }
                val run = recommend("observed_${speed}mps")
                assertEquals(RecommendationOrigin.AI_MODEL, run.origin)
                assertNotNull(run.fusionDecision)
                val evidence = JSONObject(run.contextEvidence!!).getJSONObject("snapshot").getJSONObject("speedTimeEvidence")
                runBlocking { withTimeout(15000) { c.eventPublisher.awaitSubmittedWrites() } }
                assertTrue(evidence.getJSONArray("sourceSampleIds").length() > 0)
                val used = (0 until evidence.getJSONArray("sourceSampleIds").length()).map { evidence.getJSONArray("sourceSampleIds").getString(it) }
                assertTrue(used.all { sampleId -> rows().any { it.optString("sourceSampleId") == sampleId && it.optBoolean("sourceTimeEligible") } })
                assertTrue(run.fusionDecision!!.observed.facets.getValue("driving").isNotEmpty())
                assertEquals(drive, run.driveId)
            }
            val oldToken = monitor.connectionState().token
            monitor.requestReconnect()
            await("new connection token") { monitor.connectionState().state == CarConnectionState.CONNECTED && monitor.connectionState().token != oldToken }
            val fresh = c.drivingContext.current(drive)
            assertEquals("unknown", fresh.quality)
            recommend("rebind_requires_new_observations")
            await("post-rebind coverage") { c.drivingContext.current(drive).quality == "available" }
            val finalRun = recommend("post_rebind_fresh")
            val chosen = finalRun.musicIds.first()
            val ticket = checkNotNull(c.uiPersonalization.prepare(finalRun, chosen))
            report.put("selectedMusicId", chosen)
            lateinit var item: ListenableFuture<androidx.media3.session.LibraryResult<MediaItem>>
            main { item = player.getItem(chosen) }
            val selected = checkNotNull(item.get(30, TimeUnit.SECONDS).value)
            assertEquals(chosen, selected.mediaId)
            main { player.setMediaItems(listOf(com.example.gleo_type.context.CausalMediaItem.attach(selected, ticket.playbackRequestId))); player.prepare(); player.play() }
            await("actual mapped Media3 playing") {
                var playing = false
                main { playing = player.isPlaying && player.currentPosition >= 300 && player.currentMediaItem?.mediaId == chosen && player.playerError == null }
                playing
            }
            val playback = c.playbackSessions.currentId()
            assertNotNull(playback)
            c.causalWritesBarrier().get(15, TimeUnit.SECONDS)
            val bound = c.contextPipeline.ledger.records().single {
                it.kind == com.example.gleo_type.context.LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId == playback
            }
            assertEquals(drive, bound.ids.driveSessionId)
            assertEquals(finalRun.id, bound.ids.recommendationId)
            assertEquals(chosen, bound.ids.musicId)
            assertEquals(JSONObject(finalRun.contextEvidence!!).getJSONObject("snapshot").getString("contextSnapshotId"), bound.ids.contextSnapshotId)
            assertTrue(c.uiPersonalization.like(chosen, playback!!, drive, adapter)) // automated explicit UI API, isolated profile
            val cases = ExperienceCaseRepository.forUser(fixture, c.localIdentity.id)
            val retained = cases.cases()
            assertEquals(1, retained.size)
            assertTrue(c.uiPersonalization.like(chosen, playback, drive, adapter))
            assertEquals(retained, cases.cases())
            val afterLike = recommend("after_automated_isolated_ui_like")
            assertTrue(afterLike.fusionDecision!!.scores.any { it.cbr > 0 })
            report.put("causalBinding", JSONObject().put("driveSessionId", drive).put("contextSnapshotId", bound.ids.contextSnapshotId)
                .put("recommendationId", bound.ids.recommendationId).put("playbackSessionId", playback).put("musicId", chosen)
                .put("caseCount", cases.cases().size).put("duplicateLikeAddedCase", false))
            main { player.pause() }
            await("PAUSED stored") { rows().any { it.optJSONObject("event")?.optString("label") == "PAUSED" } }
            main { player.play() }
            await("RESUMED stored") { rows().any { it.optJSONObject("event")?.optString("label") == "RESUMED" } }
            assertEquals(playback, c.playbackSessions.currentId())
            report.put("media3Played", true).put("selectedMusicId", chosen).put("callbacks", callbacks.get())
                .put("sessionPreserved", c.driveSessions.currentDriveSessionId() == drive).put("decisions", decisions)
        } finally {
            monitor.release()
            shell("cmd car_service inject-vhal-event 291504647 0.0 -t 0") // emulator input only
            main {
                report.put("playerStateAtEnd", player.playbackState).put("playWhenReadyAtEnd", player.playWhenReady)
                    .put("suppressionAtEnd", player.playbackSuppressionReason).put("itemsAtEnd", player.mediaItemCount)
                    .put("errorCodeAtEnd", player.playerError?.errorCode).put("observedMediaIdAtEnd", player.currentMediaItem?.mediaId)
                player.stop(); c.stopCollection()
            }
            runBlocking { withTimeout(15000) { c.eventPublisher.awaitSubmittedWrites() } }
            val records = rows()
            assertEquals(1, records.count { it.optString("kind") == "session_stopped" })
            assertEquals(records.size, records.map { it.optString("sequence") }.toSet().size)
            report.put("jsonlRows", records.size).put("speedRows", records.count { it.optString("kind") == "speed_sample" })
                .put("vehicleRows", records.count { it.optJSONObject("event")?.optString("domain") == "VEHICLE" })
                .put("proofPath", proof.name).put("decisions", decisions)
            proof.writeText(report.toString(), Charsets.UTF_8)
            main { player.release() }
            foreground.close()
        }
    }
}
