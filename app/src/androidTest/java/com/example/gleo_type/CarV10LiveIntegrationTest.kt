package com.example.gleo_type

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.example.gleo_type.vehicle.AAOSVehicleMonitor
import com.example.gleo_type.vehicle.CarConnectionState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.File

/** Real MainActivity and Media3. Uses Android accessibility rather than global Compose idleness.
 * Existing isolated Compose assertions remain in CarV10UiTest and GleoUiTest. */
class CarV10LiveIntegrationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val c get()=(i.targetContext.applicationContext as GleoApplication).container
    private fun await(label:String,pollMs:Long=100,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(pollMs)}
        fail(label)
    }
    private fun node(label:String):AccessibilityNodeInfo? {
        fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(n.isVisibleToUser && (n.text?.toString()==label || n.contentDescription?.toString()==label))return n
            for(k in 0 until n.childCount){val child=n.getChild(k) ?: continue;find(child)?.let{return it}}
            return null
        }
        return i.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun tap(label:String) {
        var reported=false
        fun control():AccessibilityNodeInfo? {
            val child=node(label) ?: return null
            var button=child
            while(!button.isClickable)button=button.parent ?: return null
            if(child.isEnabled && !button.isEnabled && !reported) {
                reported=true
                val dir=File(i.targetContext.filesDir,"diagnostics/car-v10-step5").apply{mkdirs()}
                File(dir,"click-readiness.jsonl").appendText(JSONObject().put("label",label)
                    .put("childEnabled",child.isEnabled).put("clickableAncestorEnabled",button.isEnabled)
                    .put("reason","wait_for_actual_button_not_description_child").toString()+"\n")
            }
            return button.takeIf {it.isEnabled && it.isVisibleToUser}
        }
        // Re-query after recomposition; a description child can be enabled while its Button is not.
        // Retry only rejected accessibility actions; never repeat an accepted playback command.
        await("Accepted click on actual enabled control: $label") {
            control()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        }
    }
    @Test fun actualAppOwnedWindowCapture() {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            await("entry or pending summary"){node("차량 플레이어")!=null || node("닫기")!=null}
            if(node("닫기")!=null)tap("닫기")
            tap("차량 플레이어")
            await("actual CAR surface"){node("로컬 CLAP · Media3 · Gleo 미연결 · 내부 연구용")!=null}
            val latch=java.util.concurrent.CountDownLatch(1)
            var result=-1;var bitmap:Bitmap?=null
            val facts=JSONObject().put("environment",android.os.Build.FINGERPRINT)
            scenario.onActivity { activity ->
                val view=activity.window.decorView
                bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                facts.put("windowWidthPx",view.width).put("windowHeightPx",view.height)
                    .put("displayId",view.display?.displayId).put("density",activity.resources.displayMetrics.density)
                if(android.os.Build.VERSION.SDK_INT>=30){
                    val metrics=activity.windowManager.currentWindowMetrics
                    val insets=metrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    facts.put("windowBounds",metrics.bounds.toShortString()).put("insets",listOf(insets.left,insets.top,insets.right,insets.bottom).joinToString(","))
                }
                android.view.PixelCopy.request(activity.window,bitmap!!,{status->result=status;latch.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue("PixelCopy callback",latch.await(15,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            val dir=i.targetContext.filesDir.resolve("diagnostics/car-v10-step3").apply{mkdirs()}
            dir.resolve("app-window.png").outputStream().use{bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap!!.recycle();dir.resolve("window-facts.json").writeText(facts.toString(2))
        } finally {scenario.close()}
    }
    @Test fun actualCommandsStateRecreationAndRepeatedEntry() = actualFlow(requireQualifiedContext=true)
    /** Generic AAOS may legitimately remain unknown; require truthful fallback and live media. */
    @Test fun actualMediaAndLifecycleWithoutAssumingVehicleQuality() = actualFlow(requireQualifiedContext=false)
    private fun actualFlow(requireQualifiedContext:Boolean) {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var controller:MediaController?=null
        lateinit var activity:MainActivity
        try {
            scenario.onActivity{activity=it}
            fun monitor()=MainActivity::class.java.getDeclaredField("aaosMonitor").apply{isAccessible=true}.get(activity) as AAOSVehicleMonitor
            await("main entry"){node("차량 플레이어")!=null}
            if(node("닫기")!=null)tap("닫기")
            val drive=c.driveSessions.currentDriveSessionId()
            assertNotNull("Actual collection session",drive)
            val before=monitor().connectionState()
            tap("차량 플레이어")
            var f:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            i.runOnMainSync{f=MediaController.Builder(i.targetContext,SessionToken(i.targetContext,ComponentName(i.targetContext,MusicPlaybackService::class.java))).buildAsync()}
            controller=f!!.get(30,TimeUnit.SECONDS)
            fun verifyMetadata() {
                var title:String?=null;var artist:String?=null
                i.runOnMainSync{title=controller!!.mediaMetadata.title?.toString();artist=controller!!.mediaMetadata.artist?.toString()}
                assertNotNull(title);assertNotNull(artist)
                await("actual title + artist in UI"){node(title!!)!=null && node(artist!!)!=null}
            }
            if(c.musicState.currentSnapshot()?.isPlaying==true){tap("일시정지");await("pause existing"){c.musicState.currentSnapshot()?.isPlaying==false}}
            tap("재생");await("actual playing"){c.musicState.currentSnapshot()?.isPlaying==true}
            val first=c.musicState.currentSnapshot()!!
            assertNotNull(first.playbackSessionId)
            verifyMetadata();await("UI playing"){node("음악 재생 중")!=null}
            tap("일시정지");await("actual pause"){c.musicState.currentSnapshot()?.isPlaying==false}
            tap("재생");await("actual resume"){c.musicState.currentSnapshot()?.isPlaying==true}
            assertEquals(first.playbackSessionId,c.musicState.currentSnapshot()!!.playbackSessionId)
            tap("다음 곡");await("actual next"){c.musicState.currentSnapshot()?.let{it.musicId!=first.musicId && it.isPlaying}==true}
            val next=c.musicState.currentSnapshot()!!;assertNotEquals(first.playbackSessionId,next.playbackSessionId);verifyMetadata()
            tap("이전 곡");await("actual previous"){c.musicState.currentSnapshot()?.let{it.musicId==first.musicId && it.isPlaying}==true};verifyMetadata()
            repeat(2){tap("모바일 화면으로");tap("차량 플레이어")}
            assertEquals(drive,c.driveSessions.currentDriveSessionId())
            val after=monitor().connectionState()
            if(before.state==CarConnectionState.CONNECTED){assertEquals(before.token,after.token);assertEquals(before.subscriptions,after.subscriptions)}
            val report=JSONObject().put("environment",android.os.Build.FINGERPRINT).put("source","REAL_MEDIA3_MAIN_ACTIVITY")
                .put("qualifiedContextRequired",requireQualifiedContext)
                .put("firstMusicId",first.musicId).put("nextMusicId",next.musicId).put("firstPlaybackSessionId",first.playbackSessionId)
                .put("driveSessionId",drive).put("transport",after.state.name).put("subscriptions",after.subscriptions)
                .put("contextBeforeRecreate",c.drivingContext.current(drive).toString())
            // Public client stop/start; never kill the platform service or synthesize SDK samples.
            if(after.state==CarConnectionState.CONNECTED) {
                if(requireQualifiedContext) {
                    await("valid pre-disconnect context and raw gate",500){c.drivingContext.current(drive).quality=="available" && c.realtime.usableContext()!=null}
                    await("UI valid before disconnect"){nodeTextContains("관측 평균 속도")}
                } else {
                    val current=c.drivingContext.current(drive)
                    report.put("genericObservedContext",current.toString()).put("genericBufferDiagnostics",c.realtime.snapshot()?.diagnostics.toString())
                    if(current.quality=="available")await("truthful available UI"){nodeTextContains("관측 평균 속도")}
                    else await("truthful unknown UI"){nodeTextContains("차량 맥락 unknown")}
                }
                monitor().stopMonitoring()
                await("actual client disconnected"){monitor().connectionState().state==CarConnectionState.STOPPED}
                val offline=c.drivingContext.current(drive)
                assertEquals("unknown",offline.quality);assertNull(offline.averageSpeedKmh)
                report.put("disconnectedContext",offline.toString())
                await("UI unavailable while music remains usable"){nodeTextContains("차량 연결 unavailable")}
                assertTrue(c.musicState.currentSnapshot()?.isPlaying==true)
                val reconnectAt=SystemClock.elapsedRealtime()
                monitor().startMonitoring()
                await("actual re-registration"){monitor().connectionState().let{it.state==CarConnectionState.CONNECTED && it.token!=after.token}}
                val input=c.drivingContext.current(drive)
                assertEquals("unknown",input.quality)
                report.put("reconnectedContext",input.toString())
                await("UI reports unknown after reconnect"){nodeTextContains("차량 맥락 unknown")}
                var post=c.drivingContext.current(drive)
                if(requireQualifiedContext)await("fresh qualified context, not a fixed dwell",500){
                    post=c.drivingContext.current(drive)
                    post.quality=="available" && post.latestObservedElapsedMs?.let{it>reconnectAt}==true &&
                        post.connectionToken==monitor().connectionState().token && c.realtime.usableContext()!=null
                }
                report.put("postReconnectObservation",post.toString()).put("freshContextValidated",post.quality=="available")
                if(post.quality=="available")await("UI valid post-reconnect context"){nodeTextContains("관측 평균 속도")}
                else await("UI still unknown when observations insufficient"){nodeTextContains("차량 맥락 unknown")}
            }
            activity=replaceActivity(activity)
            assertEquals("Configuration replacement preserves the collection",drive,c.driveSessions.currentDriveSessionId())
            report.put("driveAfterRecreate",c.driveSessions.currentDriveSessionId())
            await("restored CAR V10"){node("모바일 화면으로")!=null}
            await("restored playing"){node("음악 재생 중")!=null};verifyMetadata()
            tap("일시정지");await("final pause"){c.musicState.currentSnapshot()?.isPlaying==false}
            i.runOnMainSync{MainActivity::class.java.getDeclaredMethod("stopAllMonitoring").apply{isAccessible=true}.invoke(activity)}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(45000){c.eventPublisher.awaitSubmittedWrites()}}
            val dir=i.targetContext.filesDir.resolve("diagnostics/car-v10-step3").apply{mkdirs()}
            report.put("driveAfterExplicitStop",JSONObject.NULL).put("complete",true)
            dir.resolve("live-result.json").writeText(report.toString(2))
            val image=i.uiAutomation.takeScreenshot()
            if(image!=null){dir.resolve("live-player.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        } finally {i.runOnMainSync{controller?.pause();controller?.release()};finishActivity(activity);scenario.close();
            kotlinx.coroutines.runBlocking{kotlinx.coroutines.withTimeout(45000){c.eventPublisher.awaitSubmittedWrites()}}}
    }
    private fun nodeTextContains(part:String):Boolean {
        fun find(n:AccessibilityNodeInfo):Boolean {
            if(n.isVisibleToUser && n.text?.contains(part)==true)return true
            return (0 until n.childCount).any{k->n.getChild(k)?.let(::find)==true}
        }
        return i.uiAutomation.rootInActiveWindow?.let(::find)==true
    }
}
