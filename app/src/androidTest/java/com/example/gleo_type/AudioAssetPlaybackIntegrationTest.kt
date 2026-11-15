package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.*
import com.example.gleo_type.recommendation.RecommendationModelAdapter
import com.example.gleo_type.ui.app.*
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real production Service subclass, Player, mapper and JSONL; all writes under unique cache roots. */
class AudioAssetPlaybackIntegrationTest {
    @get:Rule val activity = createAndroidComposeRule<CarV10TestActivity>()
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun main(action: () -> Unit) = i.runOnMainSync(action)
    private fun <T> request(action: () -> ListenableFuture<T>): T {
        lateinit var f: ListenableFuture<T>; main { f = action() }; return f.get(45, TimeUnit.SECONDS)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(50) }; fail(label)
    }
    private fun fresh() = File(context.cacheDir, "audio-registry-${UUID.randomUUID()}").apply { mkdirs() }
    private fun connect(root: File): MediaBrowser {
        RegistryPlaybackTestService.testRoot = root
        return request { MediaBrowser.Builder(context, SessionToken(context,
            ComponentName(context, RegistryPlaybackTestService::class.java))).buildAsync() }
    }
    private fun disconnect(b: MediaBrowser) {
        val count = RegistryPlaybackTestService.destructions
        main { b.stop(); b.clearMediaItems() }
        await("Isolated queue drained before unbinding") { var empty=false;main {empty=b.mediaItemCount==0};empty }
        main { b.release() }
        context.stopService(Intent(context, RegistryPlaybackTestService::class.java))
        await("Isolated service stopped") { RegistryPlaybackTestService.destructions > count }
    }
    private fun catalog(b: MediaBrowser): List<MediaItem> {
        val root = request { b.getLibraryRoot(null) }.value!!
        val all = mutableListOf<MediaItem>(); var page = 0
        while (true) {
            val items = request { b.getChildren(root.mediaId, page++, 100, null) }.value!!
            all.addAll(items); if (items.size < 100) return all
        }
    }
    private fun rows(root: File): List<JSONObject> = File(root, "journal/events").listFiles().orEmpty()
        .filter { it.extension == "jsonl" }.flatMap { it.readLines().filter(String::isNotBlank).map(::JSONObject) }
    private fun install(root: File, d: AudioAssetRegistry.Descriptor, file: File) {
        val dir = File(root, "audio-assets").apply { mkdirs() }
        File(dir, AndroidAudioAssetCatalog.INSTALL_MANIFEST).writeText(JSONObject().put("formatVersion",1)
            .put("tracks", JSONArray().put(JSONObject().put("musicId",d.musicId).put("driveFileId",d.driveFileId)
                .put("relativePath","audio/${d.fileName}").put("sizeBytes",file.length())
                .put("sha256",AudioAssetRegistry.sha256(file)))).toString())
    }

    @Test fun noInstalledAudioPreservesAndPlaysAllElevenLegacyIdsInIsolatedJournal() {
        val root = fresh(); val b = connect(root); val result = JSONArray()
        try {
            val tracks = catalog(b); assertEquals(11, tracks.size)
            val c = RegistryPlaybackTestService.activeContainer!!; main { c.startCollection(); b.volume = 0f }
            for (track in tracks) {
                main { b.setMediaItem(MediaItem.Builder().setMediaId(track.mediaId).build()); b.prepare(); b.play() }
                await("Legacy decode ${track.mediaId}") { var ok=false; main {
                    ok=b.isPlaying && b.currentPosition>=350 && b.currentMediaItem?.mediaId==track.mediaId && b.playerError==null }; ok }
                result.put(JSONObject().put("musicId",track.mediaId).put("decodedProgress",true))
                main { b.pause() }
            }
            main { b.stop(); c.stopCollection() }
            await("Journal drained") { rows(root).any { it.optString("kind")=="session_stopped" } }
            assertFalse(rows(root).any { it.optJSONObject("event")?.optString("label")=="ERROR" })
            File(root,"result.json").writeText(JSONObject().put("scope","legacy11_real_service_isolated")
                .put("tracks",result).put("productionUserDataUsed",false).toString(2))
            println("AUDIO_REGISTRY_RESULT="+File(root,"result.json").absolutePath)
        } finally { disconnect(b) }
    }

    @Test fun syntheticUndecodableFileEmitsErrorNeverCompletedAndLeavesCatalog() {
        val root = fresh()
        val asset = JSONObject(context.assets.open(AndroidAudioAssetCatalog.CATALOG_ASSET).bufferedReader().use { it.readText() })
            .getJSONArray("tracks").getJSONObject(0)
        val d = AudioAssetRegistry.Descriptor(asset.getString("musicId"),asset.getString("driveFileId"),
            asset.getString("fileName"),asset.getLong("sizeBytes"),asset.getString("title"),asset.getString("artist"))
        val file = File(root,"audio-assets/audio/${d.fileName}").apply { parentFile!!.mkdirs() }
        RandomAccessFile(file,"rw").use { it.setLength(d.sizeBytes) } // synthetic invalid zero bytes, never an original
        install(root,d,file); val b = connect(root)
        try {
            assertEquals(12,catalog(b).size)
            val c=RegistryPlaybackTestService.activeContainer!!; main { c.startCollection(); b.volume=0f
                b.setMediaItem(MediaItem.Builder().setMediaId(d.musicId).build()); b.prepare(); b.play() }
            await("Actual decoder failure") { var error=false; main { error=b.playerError!=null }; error }
            await("ERROR persisted") { rows(root).any { it.optJSONObject("event")?.optString("label")=="ERROR" } }
            assertFalse(rows(root).any { it.optJSONObject("event")?.optString("label")=="COMPLETED" })
            assertFalse(catalog(b).any { it.mediaId==d.musicId })
            main { c.stopCollection() }
            File(root,"result.json").writeText(JSONObject().put("scope","SYNTHETIC_INVALID_BYTES_NEGATIVE_TEST")
                .put("errorObserved",true).put("completed",false).put("excludedFromCatalog",true).toString(2))
            println("AUDIO_REGISTRY_RESULT="+File(root,"result.json").absolutePath)
        } finally { disconnect(b) }
    }

    @Test fun approvedThreeOriginalsRecommendPlayJournalAndReopenWithSameIds() {
        val root=File(context.cacheDir,"audio-registry-approved-samples")
        val assets=File(root,"audio-assets")
        assumeTrue("Original sample transfer has not been approved/staged",File(assets,AndroidAudioAssetCatalog.INSTALL_MANIFEST).isFile)
        val expected=listOf("jamendo_api_1204669","jamendo_api_1141572","jamendo_api_1161940")
        val registry=AndroidAudioAssetCatalog.load(context,assets)
        assertTrue(expected.all { registry.isAvailable(it) })
        val modelRoot=File(root,"model-fixture")
        assumeTrue("Isolated model bundle not staged",File(modelRoot,"models/clap500/manifest.json").isFile)
        val model=RecommendationModelAdapter(modelRoot,"ISOLATED_AUDIO_SAMPLE_"+UUID.randomUUID())
        assertTrue(model.load()); model.initialize(setOf("pop"),setOf("happy"))
        val b=connect(root)
        try {
            val tracks=catalog(b);assertEquals(14,tracks.size)
            val c=RegistryPlaybackTestService.activeContainer!!;main {c.startCollection();b.volume=0f}
            fun currentRows()=rows(root).filter { it.optString("runId")==c.runId }
            val run=model.recommend(tracks.map {Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())},
                MusicScope.THREE,emptySet(),emptySet(),0,null,c.driveSessions.currentDriveSessionId())
            assertEquals(RecommendationOrigin.AI_MODEL,run.origin);assertEquals(expected.toSet(),run.musicIds.toSet())
            val car=model.carSnapshot(tracks.map {Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())},
                emptySet(),c.driveSessions.currentDriveSessionId())
            assertEquals(expected.toSet(),car.playableRecommendations.map {it.trackId}.toSet())
            val observations=JSONArray()
            for(id in run.musicIds) {
                main { b.setMediaItem(MediaItem.Builder().setMediaId(id).build());b.prepare();b.play() }
                await("Original progresses $id") {var ok=false;main {ok=b.isPlaying && b.currentPosition>=500 && b.playerError==null && b.currentMediaItem?.mediaId==id};ok}
                var actual:String?=null;var position=0L;var duration=0L;var playback:String?=null
                main {actual=b.currentMediaItem?.mediaId;position=b.currentPosition;duration=b.duration
                    playback=c.playbackSessions.currentId();c.speedSamples.accept(10f,SystemClock.elapsedRealtime(),null);b.pause() }
                await("Same ID STARTED/PAUSED JSONL") {
                    val events=currentRows().filter { it.optString("playbackSessionId")==playback &&
                        it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==id }
                    events.any {it.optJSONObject("event")?.optString("label")=="STARTED"} &&
                        events.any {it.optJSONObject("event")?.optString("label")=="PAUSED"}
                }
                observations.put(JSONObject().put("requestedMusicId",id).put("observedMusicId",actual)
                    .put("positionMs",position).put("durationMs",duration).put("playbackSessionId",playback).put("decodedProgress",true))
            }
            main {b.stop();c.stopCollection()}
            await("Vehicle sample preserved in isolated journal") {currentRows().any {it.optString("kind")=="speed_sample"}}
            File(root,"result.json").writeText(JSONObject().put("scope","THREE_APPROVED_DRIVE_ORIGINALS_REAL_PRODUCTION_SERVICE_SUBCLASS")
                .put("runId",c.runId).put("recommendationOrigin",run.origin.name).put("tracks",observations).put("syntheticSpeedInput",true)
                .put("actualPleosVehicleVerified",false).put("productionUserDataUsed",false).toString(2))
        } finally {disconnect(b)}
        val reopened=connect(root)
        try {assertTrue(catalog(reopened).map {it.mediaId}.containsAll(expected))} finally {disconnect(reopened)}
        val app=context.applicationContext as GleoApplication
        val lazy=GleoApplication::class.java.getDeclaredField("container\$delegate").apply {isAccessible=true}.get(app) as Lazy<*>
        assertFalse("Production container never initialized",lazy.isInitialized())
        val result=File(root,"result.json");result.writeText(JSONObject(result.readText()).put("serviceRecreatedCatalogRestored",true)
            .put("productionContainerInitialized",false).toString(2))
        println("AUDIO_REGISTRY_RESULT="+File(root,"result.json").absolutePath)
    }
}
