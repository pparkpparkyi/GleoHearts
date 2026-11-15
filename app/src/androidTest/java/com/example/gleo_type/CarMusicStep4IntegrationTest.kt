package com.example.gleo_type

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarMusicStep4IntegrationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun await(label:String,p:()->Boolean){val end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(p())return;Thread.sleep(100)};fail(label)}
    private fun node(part:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(n.isVisibleToUser && n.text?.contains(part)==true)return n
            for(k in 0 until n.childCount)n.getChild(k)?.let{visit(it)}?.let{return it}
            return null
        }
        return i.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    @Test fun real500ScoresVerifiedMappingFeatureJoinAndMedia3RecommendationPlay() {
        val c=(context.applicationContext as GleoApplication).container
        assertNull("Do not replace an existing collection",c.driveSessions.currentDriveSessionId())
        val root=File(context.cacheDir,"car-music-${UUID.randomUUID()}");val dir=File(root,"models/clap500").apply{mkdirs()}
        for(n in listOf("manifest.json","embeddings.bin"))File(context.filesDir,"models/clap500/$n").copyTo(File(dir,n))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_STEP4_PROFILE",PlaybackMappingVerifier(context)::verify)
        val catalog=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"),Track("music_001","Gleo Test Music","Local Audio"))
        assertEquals(RecommendationOrigin.TEST_CATALOG,adapter.carSnapshot(catalog,emptySet(),null).run.origin)
        adapter.initialize(setOf("indie"),setOf("energetic"))
        val snapshot=adapter.carSnapshot(catalog,emptySet(),null)
        assertEquals(500,snapshot.candidates.size);assertEquals(1,snapshot.candidates.count{it.playable})
        val chosen=snapshot.playableRecommendations.single()
        assertEquals("jamendo_api_1559614",chosen.trackId);assertEquals("jamendo_0010",chosen.mediaId)
        assertTrue(snapshot.candidates.filterNot{it.playable}.all{it.mediaId==null})
        assertEquals(499,snapshot.excludedCandidates.size)
        val json=JSONObject(File(dir,"manifest.json").readText());val tracks=json.getJSONArray("tracks")
        val metadata=(0 until tracks.length()).map{tracks.getJSONObject(it).let{t->EmbeddingTrack(t.getString("musicId"),t.getString("genres").split('|').filter{it.isNotBlank()}.toSet(),t.getString("moods").split('|').filter{it.isNotBlank()}.toSet())}}
        val model=MusicEmbeddingRepository.load(File(dir,"embeddings.bin").readBytes(),metadata,json.getString("modelVersion"),json.getString("sha256"))
        val expected=ClapRecommendationEngine(model).rank(model.onboarding(setOf("indie"),setOf("energetic")),500).associate{it.musicId to it.score}
        snapshot.candidates.forEach{assertEquals(expected.getValue(it.trackId),it.tasteScore!!,1e-6);assertEquals(0.0,it.contextAdjustment!!,0.0)}
        val explicit=adapter.carSnapshot(catalog,emptySet(),null,MusicRequestParser.parse("신나는 음악 추천해 줘"))
        val requested=explicit.playableRecommendations.single()
        assertEquals(.08,requested.requestAdjustment!!,1e-9)
        assertEquals(requested.tasteScore!!+requested.contextAdjustment!!+requested.requestAdjustment!!,requested.finalScore!!,1e-6)
        val blocked=adapter.carSnapshot(catalog,setOf("jamendo_0010"),null)
        assertTrue(blocked.playableRecommendations.isEmpty());assertEquals("EXPLICITLY_EXCLUDED",blocked.excludedCandidates[chosen.trackId])
        val visual=CarMusicPresentationRepository(context,adapter::modelFingerprint,adapter::modelTrackIds)
        assertEquals(136.0,visual.feature(chosen)!!.bpmEstimated!!,0.0);assertNull(visual.feature(chosen)!!.energy);assertNull(visual.artwork(chosen))
        val scenario=ActivityScenario.launch<CarV10ModelTestActivity>(Intent(context,CarV10ModelTestActivity::class.java).putExtra("modelRoot",root.absolutePath))
        lateinit var activity:CarV10ModelTestActivity;scenario.onActivity{activity=it}
        try {
            await("verified recommendation in actual UI"){node("추천: Is It Right?")!=null}
            var button=node("추천: Is It Right?")!!;while(!button.isClickable)button=button.parent!!
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("actual Media3 playback"){c.musicState.currentSnapshot()?.let{it.musicId=="jamendo_0010" && it.isPlaying && it.currentPositionMs>500}==true}
            val playback=c.playbackSessions.currentId();val drive=c.driveSessions.currentDriveSessionId()
            val evidence=File(root,"recommendations/playback-evidence.jsonl")
            await("actual start evidence, not just a click"){evidence.exists() && evidence.readLines().map{JSONObject(it)}.any{it.optString("stage")=="observed_started" && it.optString("playbackSessionId")==playback}}
            val rows=evidence.readLines().map{JSONObject(it)}
            val start=rows.single{it.optString("stage")=="observed_started"}
            assertTrue(rows.any{it.optString("stage")=="selected" && it.getString("recommendationRunId")==start.getString("recommendationRunId")})
            assertTrue(rows.first{it.optString("stage")=="selected"}.isNull("playbackSessionId"))
            val monitor=ActivityLifecycleMonitorRegistry.getInstance();val replaced=AtomicReference<CarV10ModelTestActivity>();val ready=CountDownLatch(1)
            val old=activity;val callback=ActivityLifecycleCallback{a,s->if(a is CarV10ModelTestActivity && a!==old && s==Stage.RESUMED){replaced.set(a);ready.countDown()}}
            i.runOnMainSync{monitor.addLifecycleCallback(callback);old.recreate()}
            try{assertTrue(ready.await(45,TimeUnit.SECONDS));activity=replaced.get()}finally{i.runOnMainSync{monitor.removeLifecycleCallback(callback)}}
            await("restored actual track UI"){node("Is It Right?")!=null && node("Lower Loveday")!=null}
            assertEquals(drive,c.driveSessions.currentDriveSessionId());assertEquals(playback,c.playbackSessions.currentId())
            val report=JSONObject().put("environment",android.os.Build.FINGERPRINT).put("modelRows",500).put("playableMappings",1)
                .put("scoredRowsCompared",500).put("maxTolerance",1e-6).put("selectedMediaId","jamendo_0010").put("bpmEstimated",136)
                .put("actualAlbumArt","UNAVAILABLE_NO_APPROVAL").put("preferenceProfile","SYNTHETIC_ISOLATED").put("passed",true)
            File(context.filesDir,"diagnostics/car-v10-step4/result.json").apply{parentFile!!.mkdirs();writeText(report.toString(2))}
            val readyFrame=CountDownLatch(1);var image:android.graphics.Bitmap?=null;var copied=-1
            i.runOnMainSync {
                val view=activity.window.decorView;image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(activity.window,image!!,{r->copied=r;readyFrame.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue(readyFrame.await(15,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,copied)
            File(context.filesDir,"diagnostics/car-v10-step4/model-window.png").outputStream().use{image!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image!!.recycle()
        } finally {
            i.runOnMainSync{if(c.musicState.currentSnapshot()?.isPlaying==true)activity.playback.toggle();c.stopCollection();activity.finish()}
            // UI is removed before scenario.close's global idle wait.
            await("Activity destroyed"){activity.isDestroyed};scenario.close()
            runBlocking{withTimeout(45000){c.eventPublisher.awaitSubmittedWrites()}}
        }
    }
}
