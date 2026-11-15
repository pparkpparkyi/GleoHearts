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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Original observed JSONL is copied without edits into an isolated test profile's input directory. */
class CatalogPlaybackFlowTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun <T> request(action:()->ListenableFuture<T>):T {
        lateinit var f:ListenableFuture<T>; i.runOnMainSync { f=action() }; return f.get(30,TimeUnit.SECONDS)
    }
    private fun await(name:String,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<until){if(condition())return;Thread.sleep(100)}
        fail(name)
    }
    private fun fixture():File=File(context.cacheDir,"catalog-flow-${UUID.randomUUID()}").also { root ->
        val dest=File(root,"models/clap500");dest.mkdirs()
        for(name in listOf("manifest.json","embeddings.bin")) File(context.filesDir,"models/clap500/$name").copyTo(File(dest,name))
    }
    private fun adapter(root:File,user:String)=RecommendationModelAdapter(root,user,PlaybackMappingVerifier(context)::verify)
    private fun profile(root:File)=File(root,"recommendation-model").walkTopDown().single { it.isFile && it.extension=="jsonl" }
    private fun snapshot(root:File)=JSONObject(profile(root).readLines(Charsets.UTF_8).last())
    private fun vector(row:JSONObject)=row.getJSONArray("vector").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun engine(root:File):ClapRecommendationEngine {
        val dir=File(root,"models/clap500");val j=JSONObject(File(dir,"manifest.json").readText(Charsets.UTF_8));val rows=j.getJSONArray("tracks")
        val tracks=(0 until rows.length()).map { EmbeddingTrack(rows.getJSONObject(it).getString("musicId")) }
        return ClapRecommendationEngine(MusicEmbeddingRepository.load(File(dir,"embeddings.bin").readBytes(),tracks,j.getString("modelVersion"),j.getString("sha256")))
    }
    @Test fun onboardingRecommendationActualPlaybackLikeAndPreferenceUpdate() {
        val c=(context.applicationContext as GleoApplication).container
        val root=fixture();val a=adapter(root,c.localIdentity.id)
        a.initialize(setOf("indie"),setOf("energetic"))
        val initial=vector(snapshot(root))
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val browser=request { MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
        try {
            await("drive starts") { c.driveSessions.currentDriveSessionId()!=null }
            val drive=c.driveSessions.currentDriveSessionId()!!
            val library=request { browser.getLibraryRoot(null) }.value!!
            val items=request { browser.getChildren(library.mediaId,0,100,null) }.value!!
            val catalog=items.map { Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString()) }
            val first=a.recommend(catalog,MusicScope.THREE,emptySet(),emptySet(),0,null,drive)
            assertEquals(RecommendationOrigin.AI_MODEL,first.origin);assertEquals(listOf("jamendo_0010"),first.musicIds)
            i.runOnMainSync { browser.setMediaItem(items.single { it.mediaId==first.musicIds.single() });browser.prepare();browser.play() }
            await("real playback progress") { var okay=false;i.runOnMainSync { okay=browser.isPlaying && browser.currentPosition>=4000 };okay }
            val pb=c.playbackSessions.currentId()!!
            assertTrue(a.like(c.runId,drive,pb,"jamendo_0010"))
            i.runOnMainSync { c.stopCollection() }
            fun rows()=File(context.filesDir,"events").listFiles().orEmpty().filter { it.name.startsWith(c.runId) && it.extension=="jsonl" }
                .flatMap { it.readLines(Charsets.UTF_8) }.mapNotNull { line -> runCatching { line to JSONObject(line) }.getOrNull() }
                .filter { it.second.optString("driveSessionId")==drive }
            await("original listening summary flushed") { rows().any { it.second.optString("kind")=="listening_summary" && it.second.optString("playbackSessionId")==pb } }
            val observed=rows()
            val summary=observed.single { it.second.optString("kind")=="listening_summary" && it.second.optString("playbackSessionId")==pb }.second
            assertEquals("jamendo_0010",summary.getString("musicId"))
            assertTrue(observed.any { it.second.optJSONObject("event")?.optString("label")=="STARTED" && it.second.optString("playbackSessionId")==pb })
            val source=File(root,"events/observed.jsonl");source.parentFile!!.mkdirs()
            source.writeText(observed.joinToString("\n",postfix="\n") { it.first },Charsets.UTF_8)
            val next=a.recommend(catalog,MusicScope.THREE,emptySet(),emptySet(),1,null,drive)
            val after=snapshot(root);val v=vector(after)
            assertEquals("Observed summary must safely allow the pending like: $summary",1,after.getInt("learnedCount"))
            assertEquals(1,after.getJSONArray("processed").length())
            val delta=kotlin.math.sqrt(initial.indices.sumOf { (initial[it]-v[it]).toDouble()*(initial[it]-v[it]) })
            assertTrue(delta>1e-5)
            assertTrue("The only verified candidate is now previously listened",next.musicIds.isEmpty())
            val rankBefore=engine(root).rank(initial,500);val rankAfter=engine(root).rank(v,500)
            val target="jamendo_api_1559614"
            val scoreBefore=rankBefore.single { it.musicId==target }.score
            val scoreAfter=rankAfter.single { it.musicId==target }.score
            assertTrue(scoreAfter>scoreBefore)
            val lines=profile(root).readLines().size
            a.recommend(catalog,MusicScope.ALL,emptySet(),emptySet(),0,null,null)
            assertEquals(lines,profile(root).readLines().size)
            val diagnostic=JSONObject().put("result","passed").put("environment",android.os.Build.FINGERPRINT)
                .put("testProfileRoot",root.absolutePath).put("testProfileKind","isolated_test_onboarding_with_unmodified_observed_session")
                .put("runId",c.runId).put("driveSessionId",drive).put("playbackSessionId",pb)
                .put("playableBefore",first.musicIds.size).put("playableAfter",next.musicIds.size)
                .put("playbackMusicId","jamendo_0010").put("modelMusicId",target)
                .put("scoreBefore",scoreBefore).put("scoreAfter",scoreAfter).put("vectorDeltaL2",delta)
                .put("learnedCount",1).put("profileLines",lines).put("vectorHash",hash(after.getJSONArray("vector").toString()))
                .put("summaryConfidence",summary.getString("confidence")).put("summaryFlags",summary.getJSONArray("qualityFlags"))
                .put("uniqueListeningRate",summary.getString("uniqueListeningRate"))
                .put("internalTop10Changed",rankBefore.take(10).map { it.musicId }!=rankAfter.take(10).map { it.musicId })
            val dest=File(context.filesDir,"diagnostics/catalog-playback-flow.json");dest.parentFile!!.mkdirs();dest.writeText(diagnostic.toString(2),Charsets.UTF_8)
        } finally { i.runOnMainSync { browser.stop();browser.release();c.stopCollection();activity.finish() } }
    }
    /** Host launches this method in a second instrumentation process after force-stop. */
    @Test fun restartRecoversProfileWithoutRelearning() {
        val c=(context.applicationContext as GleoApplication).container
        val dest=File(context.filesDir,"diagnostics/catalog-playback-flow.json")
        val j=JSONObject(dest.readText(Charsets.UTF_8));assertNotEquals(j.getString("runId"),c.runId)
        val root=File(j.getString("testProfileRoot"))
        assertTrue(root.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator))
        val a=adapter(root,c.localIdentity.id)
        val run=a.recommend(listOf(Track("jamendo_0010","Is It Right?","Lower Loveday")),MusicScope.THREE,emptySet(),emptySet(),0,null,null)
        assertEquals(RecommendationOrigin.AI_MODEL,run.origin);assertTrue(run.musicIds.isEmpty())
        assertEquals(j.getInt("profileLines"),profile(root).readLines().size)
        assertEquals(1,snapshot(root).getInt("learnedCount"))
        assertEquals(j.getString("vectorHash"),hash(snapshot(root).getJSONArray("vector").toString()))
        j.put("restartVerified",true).put("restartRunIdDifferent",true);dest.writeText(j.toString(2),Charsets.UTF_8)
    }
    @Test fun playbackErrorDoesNotTrainOrExcludeAndSeekDoesNotRewardCompletion() {
        val root=fixture();val user="SYNTHETIC_ERROR_TEST";val a=adapter(root,user)
        a.initialize(setOf("indie"),setOf("energetic"));val before=snapshot(root).getJSONArray("vector").toString()
        val common=JSONObject().put("runId","synthetic").put("driveSessionId","drive").put("playbackSessionId","error")
            .put("localUserId",user).put("musicId","jamendo_0010")
        val error=JSONObject(common.toString()).put("kind","event").put("event",JSONObject().put("label","ERROR"))
        val summary=JSONObject(common.toString()).put("kind","listening_summary").put("qualityFlags",JSONArray())
            .put("confidence","bounded_callback_estimate").put("finalSnapshotAvailable","true")
            .put("uniqueListeningRate","0.99").put("totalListeningWallMs","1500.0").put("closeReason","natural_end")
        val source=File(root,"events/synthetic.jsonl");source.parentFile!!.mkdirs();source.writeText("$error\n$summary\n",Charsets.UTF_8)
        val run=a.recommend(listOf(Track("jamendo_0010","Is It Right?","Lower Loveday")),MusicScope.THREE,emptySet(),emptySet(),0,null,null)
        assertEquals(listOf("jamendo_0010"),run.musicIds)
        assertEquals(0,snapshot(root).getInt("learnedCount"));assertEquals(before,snapshot(root).getJSONArray("vector").toString())
        val seekRoot=fixture();val seekAdapter=adapter(seekRoot,user)
        seekAdapter.initialize(setOf("indie"),setOf("energetic"))
        val seekEvent=JSONObject(error.toString()).put("event",JSONObject().put("label","SEEKED"))
        val seekInput=File(seekRoot,"events/synthetic-seek.jsonl");seekInput.parentFile!!.mkdirs()
        seekInput.writeText("$seekEvent\n$summary\n",Charsets.UTF_8)
        seekAdapter.recommend(listOf(Track("jamendo_0010","Is It Right?","Lower Loveday")),MusicScope.THREE,emptySet(),emptySet(),0,null,null)
        assertEquals("feedback:0.2",snapshot(seekRoot).getString("reason")) // Verified credit, never the +0.6 completion reward.
    }
}
