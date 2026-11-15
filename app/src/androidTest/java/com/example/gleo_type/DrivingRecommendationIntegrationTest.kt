package com.example.gleo_type

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DrivingRecommendationIntegrationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun await(label:String,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+60_000
        while(SystemClock.elapsedRealtime()<end) { if(predicate())return;Thread.sleep(50) };fail(label)
    }
    @Test fun actualLiveSdkProviderAndReconnectUseOnlyCurrentQuality() {
        val c=(context.applicationContext as GleoApplication).container
        assertNull("Do not replace an existing collection",c.driveSessions.currentDriveSessionId())
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor=MainActivity::class.java.getDeclaredField("aaosMonitor").apply { isAccessible=true }.get(activity) as AAOSVehicleMonitor
        val root=File(context.cacheDir,"driving-live-${UUID.randomUUID()}")
        val dir=File(root,"models/clap500");dir.mkdirs()
        for(n in listOf("manifest.json","embeddings.bin")) File(context.filesDir,"models/clap500/$n").copyTo(File(dir,n))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_LIVE_PROFILE",PlaybackMappingVerifier(context)::verify,
            drivingContext={ d -> c.drivingContext.current(d) })
        val report=JSONObject().put("environment",android.os.Build.FINGERPRINT).put("source","LIVE_SDK")
        try {
            await("Actual transport and collection") { c.driveSessions.currentDriveSessionId()!=null && monitor.connectionState().state==CarConnectionState.CONNECTED }
            val drive=c.driveSessions.currentDriveSessionId()!!
            adapter.initialize(setOf("indie"),setOf("energetic"))
            Thread.sleep(11000) // Actual observation only, no speed injection or cached repeats.
            val tracks=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"))
            val run=adapter.recommend(tracks,MusicScope.THREE,emptySet(),emptySet(),0,null,drive)
            val d=run.drivingDecision!!
            assertEquals(RecommendationOrigin.AI_MODEL,run.origin);assertEquals(listOf("jamendo_0010"),run.musicIds)
            assertEquals(ContextInputSource.LIVE_SDK,d.input!!.source)
            if(d.input.quality=="available") assertTrue(d.applied) else {
                assertFalse(d.applied);assertNotNull(d.fallbackReason)
                assertTrue(d.scores.all { it.contextCorrection==0.0 && it.finalScore==it.preferenceScore })
            }
            val history=LocalRecommendationHistory(File(root,"recommendations"));history.append(run)
            assertEquals(run,history.load().single())
            // Legacy history without optional extension must remain readable.
            val file=File(root,"recommendations/runs.jsonl")
            val old=JSONObject(file.readText().trim());old.remove("drivingDecision")
            val legacy=File(root,"legacy");legacy.mkdirs();File(legacy,"runs.jsonl").writeText(old.toString()+"\n")
            assertNull(LocalRecommendationHistory(legacy).load().single().drivingDecision)
            val token=monitor.connectionState().token;monitor.requestReconnect()
            await("Reconnected") { monitor.connectionState().state==CarConnectionState.CONNECTED && monitor.connectionState().token!=token }
            val after=c.drivingContext.current(drive)
            assertEquals("unknown",after.quality)
            val fallback=adapter.recommend(tracks,MusicScope.THREE,emptySet(),emptySet(),0,null,drive).drivingDecision!!
            assertFalse(fallback.applied);assertEquals(drive,c.driveSessions.currentDriveSessionId())
            await("Fresh post-connection SDK observations restore usable context") {
                c.drivingContext.current(drive).quality=="available"
            }
            val recovered=adapter.recommend(tracks,MusicScope.THREE,emptySet(),emptySet(),0,null,drive).drivingDecision!!
            assertTrue(recovered.applied);assertEquals(drive,c.driveSessions.currentDriveSessionId())
            assertNotEquals(d.input.connectionToken,recovered.input!!.connectionToken)
            report.put("passed",true).put("initialDecision",DrivingDecisionJson.encode(d))
                .put("immediateReconnectionDecision",DrivingDecisionJson.encode(fallback))
                .put("freshReconnectionDecision",DrivingDecisionJson.encode(recovered))
                .put("actualPlayableIds",org.json.JSONArray(run.musicIds)).put("playbackAttempted",false)
        } finally {
            i.runOnMainSync { activity.finish() }
            await("Release") { monitor.connectionState().state==CarConnectionState.RELEASED }
            val diagnostics=File(context.filesDir,"diagnostics");diagnostics.mkdirs()
            File(diagnostics,"driving-recommendation-${System.currentTimeMillis()}.json").writeText(report.toString(),Charsets.UTF_8)
        }
    }
}
