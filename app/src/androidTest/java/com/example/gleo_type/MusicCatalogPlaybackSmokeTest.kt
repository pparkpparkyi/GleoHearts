package com.example.gleo_type

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Existing research/debug audio only. No download, new mapping or distribution grant. */
class MusicCatalogPlaybackSmokeTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun main(action:()->Unit)=i.runOnMainSync(action)
    private fun <T> request(action:()->ListenableFuture<T>):T {
        lateinit var future:ListenableFuture<T>;main { future=action() };return future.get(30,TimeUnit.SECONDS)
    }
    private fun await(label:String,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<until) { if(condition())return;Thread.sleep(50) };fail(label)
    }
    @Test fun allElevenExistingTracksDecodeStartPauseAndPersistDistinctPlaybackSessions() {
        val context=i.targetContext;val c=(context.applicationContext as GleoApplication).container
        assertNull("Do not replace an active user collection",c.driveSessions.currentDriveSessionId())
        main { c.startCollection() };val drive=c.driveSessions.currentDriveSessionId()!!
        val browser=request { MediaBrowser.Builder(context,SessionToken(context,
            ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
        fun records()=context.filesDir.resolve("events").listFiles().orEmpty()
            .filter { it.name.startsWith(c.runId) && it.extension=="jsonl" }
            .flatMap { it.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() } }
            .filter { it.optString("driveSessionId")==drive }
        val results=JSONArray();val playbackIds=mutableSetOf<String>()
        try {
            val root=request { browser.getLibraryRoot(null) }.value!!
            val catalog=request { browser.getChildren(root.mediaId,0,100,null) }.value!!
            val expected=(1..8).map { "music_%03d".format(java.util.Locale.ROOT,it) }+
                listOf("jamendo_0010","jamendo_0016","jamendo_0020")
            assertEquals(expected,catalog.map { it.mediaId })
            main { browser.repeatMode=Player.REPEAT_MODE_OFF;browser.shuffleModeEnabled=false;browser.setPlaybackSpeed(1f) }
            for(id in expected) {
                main { browser.pause();browser.setMediaItem(MediaItem.Builder().setMediaId(id).build());browser.prepare();browser.play() }
                await("Decoded and progressing: $id") {
                    var ok=false;main { ok=browser.currentMediaItem?.mediaId==id && browser.isPlaying &&
                        browser.currentPosition>=350 && browser.duration>0 && browser.playerError==null };ok
                }
                val playback=c.playbackSessions.currentId()!!;assertTrue("New item occurrence: $id",playbackIds.add(playback))
                main { browser.pause() }
                await("Persisted STARTED/PAUSED and correct mediaId: $id") {
                    val rows=records().filter { it.optString("playbackSessionId")==playback &&
                        it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==id }
                    rows.any { it.optJSONObject("event")?.optString("label")=="STARTED" } &&
                        rows.any { it.optJSONObject("event")?.optString("label")=="PAUSED" }
                }
                var duration=0L;main { duration=browser.duration;assertFalse(browser.isPlaying) }
                results.put(JSONObject().put("musicId",id).put("playbackSessionId",playback).put("durationMs",duration)
                    .put("decodedProgressVerified",true).put("pauseVerified",true))
            }
            main { browser.stop();c.stopCollection() }
            await("FIFO collection end stored") { records().any { it.optString("kind")=="session_stopped" } }
            val rows=records();assertFalse(rows.any { it.optJSONObject("event")?.optString("label")=="ERROR" })
            assertEquals(11,playbackIds.size)
            val summaries=rows.filter { it.optString("kind")=="listening_summary" }
            assertTrue(summaries.map { it.optString("playbackSessionId") }.toSet().containsAll(playbackIds))
            val output=context.filesDir.resolve("diagnostics");output.mkdirs()
            output.resolve("music-catalog-smoke-${System.currentTimeMillis()}.json").writeText(JSONObject()
                .put("environment",android.os.Build.FINGERPRINT).put("passed",true).put("tracks",results)
                .put("playbackSuccess",11).put("playbackFailure",0).put("uniquePlaybackSessions",11)
                .put("newModelMappings",0).put("scope","existing_research_debug_assets_only")
                .put("fullDurationListeningTested",false).toString(),Charsets.UTF_8)
        } finally { main { browser.stop();browser.release();c.stopCollection() } }
    }
}
