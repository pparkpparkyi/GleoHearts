package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class ClapModelIntegrationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun <T> request(action:()->ListenableFuture<T>):T {
        lateinit var future:ListenableFuture<T>
        i.runOnMainSync { future=action() }; return future.get(30,TimeUnit.SECONDS)
    }
    private fun await(name:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<end) { if(condition())return; Thread.sleep(100) }
        fail(name)
    }
    private fun fixture():File = File(context.cacheDir,"clap-test-${UUID.randomUUID()}").also { root ->
        val dest=File(root,"models/clap500"); dest.mkdirs()
        for(name in listOf("manifest.json","embeddings.bin"))
            File(context.filesDir,"models/clap500/$name").copyTo(File(dest,name),overwrite=false)
    }
    @Test fun realModelMapsOneTrackRanksAndPlaysThroughExistingMedia3() {
        val root=fixture()
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_TEST_USER",PlaybackMappingVerifier(context)::verify)
        val c=(context.applicationContext as GleoApplication).container
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val browser=request { MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
        try {
            val library=request { browser.getLibraryRoot(null) }.value!!
            val items=request { browser.getChildren(library.mediaId,0,100,null) }.value!!
            val tracks=items.map { Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString()) }
            assertEquals(11,tracks.size)
            val available=adapter.availability(tracks.map { it.id }.toSet())
            assertTrue(available.loaded); assertFalse(available.hasPreference); assertEquals(1,available.playableCount)
            assertEquals(RecommendationOrigin.TEST_CATALOG,adapter.recommend(tracks,MusicScope.THREE,emptySet(),emptySet(),0,null,null).origin)
            adapter.initialize(setOf("indie"),setOf("energetic"))
            val run=adapter.recommend(tracks,MusicScope.THREE,emptySet(),emptySet(),0,null,c.driveSessions.currentDriveSessionId())
            assertEquals(RecommendationOrigin.AI_MODEL,run.origin)
            assertEquals(listOf("jamendo_0010"),run.musicIds)
            assertEquals(run.musicIds.toSet(),run.scores.keys)
            val history=LocalRecommendationHistory(File(root,"recommendations")); history.append(run)
            assertEquals(run,history.load().single())
            assertTrue(adapter.recommend(tracks,MusicScope.ALL,emptySet(),setOf("jamendo_0010"),0,null,null).musicIds.isEmpty())
            assertEquals(listOf("jamendo_0010"),adapter.recommend(tracks,MusicScope.ALL,emptySet(),emptySet(),0,null,null).musicIds)
            i.runOnMainSync {
                browser.setMediaItem(items.single { it.mediaId==run.musicIds.single() }); browser.prepare(); browser.play()
            }
            await("actual Media3 audio progress") {
                var playing=false
                i.runOnMainSync { playing=browser.isPlaying && browser.currentPosition>1000 && browser.currentMediaItem?.mediaId=="jamendo_0010" }
                playing
            }
            await("source JSONL STARTED") {
                File(context.filesDir,"events").listFiles().orEmpty().filter { it.name.startsWith(c.runId) }.any { f ->
                    f.readLines(Charsets.UTF_8).any { line -> runCatching { val j=JSONObject(line)
                        j.optJSONObject("event")?.optString("label")=="STARTED" &&
                            j.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")=="jamendo_0010" &&
                            j.optString("playbackSessionId")==c.playbackSessions.currentId()
                    }.getOrDefault(false) }
                }
            }
            val out=File(context.filesDir,"diagnostics/clap-integration.json"); out.parentFile!!.mkdirs()
            out.writeText(JSONObject().put("result","passed").put("environment",android.os.Build.FINGERPRINT)
                .put("runId",c.runId).put("driveSessionId",c.driveSessions.currentDriveSessionId())
                .put("modelVersion",run.modelVersion).put("media3CatalogCount",tracks.size).put("verifiedPlayableCount",1)
                .put("playbackMusicId",run.musicIds.single()).put("scores",JSONObject(run.scores))
                .put("vehicleFeaturesUsed",false).put("profileKind","isolated_synthetic_test_user").toString(2),Charsets.UTF_8)
        } finally { i.runOnMainSync { browser.stop(); browser.release(); c.stopCollection(); activity.finish() } }
    }
    @Test fun feedbackPersistenceDedupAndSeekTailAreNotFullListening() {
        val root=fixture(); val user="SYNTHETIC_FEEDBACK_TEST"
        fun adapter()=RecommendationModelAdapter(root,user,PlaybackMappingVerifier(context)::verify)
        var a=adapter(); a.initialize(setOf("indie"),setOf("energetic"))
        val catalog=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"))
        val eventDir=File(root,"events"); eventDir.mkdirs()
        val eventFile=File(eventDir,"synthetic.jsonl")
        fun summary(pb:String,rate:String)=JSONObject().put("kind","listening_summary").put("runId","test-run")
            .put("driveSessionId","test-drive").put("playbackSessionId",pb).put("musicId","jamendo_0010")
            .put("localUserId",user).put("uniqueListeningRate",rate).put("totalListeningWallMs","1500.0")
            .put("finalSnapshotAvailable","true").put("confidence","bounded_callback_estimate")
            .put("qualityFlags",JSONArray()).put("closeReason","natural_end").put("occurredAt","2026-11-11T00:00:00Z")
        eventFile.writeText(summary("seek-tail","0.01").toString()+"\n",Charsets.UTF_8)
        a.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,null)
        fun snapshots()=File(root,"recommendation-model").walkTopDown().single { it.extension=="jsonl" }
        val initial=JSONObject(snapshots().readLines().last())
        assertEquals(0,initial.getInt("learnedCount"))
        val countBefore=snapshots().readLines().size
        eventFile.appendText(summary("not-yet-committed","0.01").toString(),Charsets.UTF_8)
        a.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,null)
        assertEquals("No learning from unterminated source JSONL",countBefore,snapshots().readLines().size)
        eventFile.appendText("\n",Charsets.UTF_8)
        assertTrue(a.like("test-run","test-drive","liked","jamendo_0010"))
        eventFile.appendText(summary("liked","0.7").toString()+"\n",Charsets.UTF_8)
        a.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,null)
        val lines=snapshots().readLines().size
        val learned=JSONObject(snapshots().readLines().last()); assertEquals(1,learned.getInt("learnedCount"))
        assertTrue(snapshots().readLines().any { JSONObject(it).getString("reason")=="feedback:1.0" })
        a=adapter(); a.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,null)
        assertEquals(lines,snapshots().readLines().size)
        assertEquals(3,JSONObject(snapshots().readLines().last()).getJSONArray("processed").length())
        val broken=fixture(); File(broken,"models/clap500/embeddings.bin").appendText("corrupt")
        assertFalse(RecommendationModelAdapter(broken,user).availability(setOf("jamendo_0010")).loaded)
        assertEquals(RecommendationOrigin.TEST_CATALOG,RecommendationModelAdapter(File(root,"missing"),user)
            .recommend(catalog,MusicScope.THREE,emptySet(),emptySet(),0,null,null).origin)
    }
}
