package com.example.gleo_type

import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real service, real journal, no synthetic session rows or duplicated publish calls. */
class CarV10JournalAcceptanceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val c get()=(i.targetContext.applicationContext as GleoApplication).container
    private fun await(label:String,p:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<deadline){if(p())return;Thread.sleep(100)}
        fail(label)
    }
    private fun drain()=runBlocking{withTimeout(45000){c.eventPublisher.awaitSubmittedWrites()}}
    private fun rows()=i.targetContext.filesDir.resolve("events").listFiles().orEmpty()
        .filter{it.name.startsWith(c.runId+"-") && it.extension=="jsonl"}
        .flatMap{f->f.readLines(Charsets.UTF_8).map{JSONObject(it)}}
    @Test fun sameProcessRecreationThenExplicitStopIsDurable() {
        val report=JSONObject().put("environment",android.os.Build.FINGERPRINT).put("runId",c.runId)
        val dir=i.targetContext.filesDir.resolve("diagnostics/car-v10-acceptance").apply{mkdirs()}
        val name=InstrumentationRegistry.getArguments().getString("acceptancePhase") ?: "final"
        fun checkpoint(){dir.resolve("journal-$name.json").writeText(report.toString(2))}
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var controller:MediaController?=null
        lateinit var activity:MainActivity
        try {
            scenario.onActivity{activity=it};checkpoint()
            await("active collection"){c.driveSessions.currentDriveSessionId()!=null}
            val drive=c.driveSessions.currentDriveSessionId()!!;val owner=c;val pid=Process.myPid()
            val oldMonitor=MainActivity::class.java.getDeclaredField("aaosMonitor").apply{isAccessible=true}.get(activity) as com.example.gleo_type.vehicle.AAOSVehicleMonitor
            report.put("eventsPath",i.targetContext.filesDir.resolve("events").absolutePath)
            var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            i.runOnMainSync{future=MediaController.Builder(i.targetContext,SessionToken(i.targetContext,ComponentName(i.targetContext,MusicPlaybackService::class.java))).buildAsync()}
            controller=future!!.get(30,TimeUnit.SECONDS)
            i.runOnMainSync{controller!!.setMediaItems(listOf(MediaItem.Builder().setMediaId("music_001").build(),MediaItem.Builder().setMediaId("music_002").build()));controller!!.prepare();controller!!.play()}
            await("actual playing"){c.musicState.currentSnapshot()?.isPlaying==true}
            await("listening interval before recreate"){rows().any{it.optString("driveSessionId")==drive && it.optString("kind")=="listening_interval"}}
            drain();report.put("initialFenceCompletedElapsedMs",SystemClock.elapsedRealtime());val playback=c.playbackSessions.currentId()
            report.put("pidBefore",pid).put("containerBefore",System.identityHashCode(owner)).put("driveBefore",drive).put("playbackBefore",playback)
            checkpoint();val rotatedAt=SystemClock.elapsedRealtime();activity=replaceActivity(activity)
            await("collection after recreate"){c.driveSessions.currentDriveSessionId()!=null}
            val after=c.driveSessions.currentDriveSessionId()!!
            report.put("pidAfter",Process.myPid()).put("containerAfter",System.identityHashCode(c)).put("driveAfter",after).put("playbackAfter",c.playbackSessions.currentId())
            await("old AAOS client released"){oldMonitor.connectionState().state==com.example.gleo_type.vehicle.CarConnectionState.RELEASED}
            val newMonitor=MainActivity::class.java.getDeclaredField("aaosMonitor").apply{isAccessible=true}.get(activity) as com.example.gleo_type.vehicle.AAOSVehicleMonitor
            await("replacement AAOS connected"){newMonitor.connectionState().state==com.example.gleo_type.vehicle.CarConnectionState.CONNECTED}
            report.put("oldTransport",oldMonitor.connectionState().toString()).put("newTransport",newMonitor.connectionState().toString())
            assertNotSame(oldMonitor,newMonitor)
            checkpoint()
            await("new listening observation after recreate"){rows().any{it.optString("driveSessionId")==after && it.optString("kind")=="listening_observation" && it.optString("receivedElapsedMs").toLongOrNull()?.let{v->v>rotatedAt}==true}}
            // Same explicit action used by the collection button, not Activity destruction.
            report.put("explicitStopRequestedElapsedMs",SystemClock.elapsedRealtime())
            i.runOnMainSync{MainActivity::class.java.getDeclaredMethod("stopAllMonitoring").apply{isAccessible=true}.invoke(activity)}
            drain()
            report.put("stopFenceCompletedElapsedMs",SystemClock.elapsedRealtime())
            val saved=rows();val stopped=saved.filter{it.optString("kind")=="session_stopped"}
            report.put("rowCount",saved.size).put("kinds",JSONObject(saved.groupingBy{it.optString("kind")}.eachCount()))
                .put("stoppedDrives",JSONArray(stopped.map{it.optString("driveSessionId")})).put("sameContainer",owner===c)
                .put("lastStoredAt",saved.lastOrNull()?.optString("storedAt"))
            checkpoint()
            assertEquals("same process",pid,Process.myPid());assertSame(owner,c)
            assertEquals("Activity recreation is not collection end",drive,after)
            assertNotNull(playback);assertEquals(playback,c.playbackSessions.currentId())
            assertEquals(1,saved.count{it.optString("kind")=="session_started" && it.optString("driveSessionId")==drive})
            assertEquals(1,stopped.count{it.optString("driveSessionId")==drive})
            val sequences=saved.map{it.getString("sequence")};assertEquals(sequences.size,sequences.toSet().size)
            val ids=saved.mapNotNull{it.optJSONObject("event")?.optString("eventId")?.takeIf(String::isNotEmpty)};assertEquals(ids.size,ids.toSet().size)
            i.runOnMainSync{MainActivity::class.java.getDeclaredMethod("stopAllMonitoring").apply{isAccessible=true}.invoke(activity)}
            drain();assertEquals(1,rows().count{it.optString("kind")=="session_stopped" && it.optString("driveSessionId")==drive})
            report.put("passed",true);checkpoint()
        } finally {
            i.runOnMainSync{controller?.pause();controller?.release()}
            finishActivity(activity);scenario.close();drain();checkpoint()
        }
    }
}
