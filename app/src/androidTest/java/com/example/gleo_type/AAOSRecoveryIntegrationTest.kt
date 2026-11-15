package com.example.gleo_type

import android.os.SystemClock
import android.os.Handler
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.vehicle.AAOSVehicleMonitor
import com.example.gleo_type.vehicle.CarConnectionState
import com.example.gleo_type.vehicle.CarConnectionSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real public Car lifecycle factory and actual speed callbacks. Does NOT kill the platform service. */
class AAOSRecoveryIntegrationTest {
    private fun await(label:String, predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+60_000
        while(SystemClock.elapsedRealtime()<deadline) { if(predicate()) return; Thread.sleep(50) }
        fail(label)
    }
    @Test fun actualClientRebindRestoresSpeedWithoutChangingCollection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val container=(context.applicationContext as GleoApplication).container
        assertNull("Run only while collection is stopped; do not replace an existing user session",
            container.driveSessions.currentDriveSessionId())
        val samples=AtomicInteger();val states=CopyOnWriteArrayList<CarConnectionSnapshot>()
        val monitor=AAOSVehicleMonitor(context,onSpeedSample={mps,elapsed,sourceTime ->
            samples.incrementAndGet();container.speedSamples.accept(mps,elapsed,sourceTime)
        },onConnectionState={states+=it},onEvent={})
        val report=JSONObject().put("environment",android.os.Build.FINGERPRINT)
            .put("mode","real_Car_client_requested_rebind_not_service_crash")
        try {
            container.startCollection();val drive=container.driveSessions.currentDriveSessionId()!!
            val run=container.runId
            monitor.startMonitoring()
            await("Initial lifecycle readiness") { monitor.connectionState().state==CarConnectionState.CONNECTED }
            await("Initial actual SDK speed") { samples.get()>0 }
            repeat(3) {
                val before=samples.get();val token=monitor.connectionState().token
                monitor.requestReconnect()
                await("Rebound new Car and subscriptions") { monitor.connectionState().state==CarConnectionState.CONNECTED &&
                    monitor.connectionState().token!=token }
                await("Actual speed callback resumes after rebind") { samples.get()>before }
                assertEquals(drive,container.driveSessions.currentDriveSessionId());assertEquals(run,container.runId)
            }
            monitor.stopMonitoring()
            await("Collection transport stopped") { monitor.connectionState().state==CarConnectionState.STOPPED }
            val before=samples.get();Thread.sleep(1200);assertEquals(before,samples.get())
            monitor.startMonitoring()
            await("New collection transport start") { monitor.connectionState().state==CarConnectionState.CONNECTED }
            await("Speed resumes after start-stop-start") { samples.get()>before }
            assertEquals(drive,container.driveSessions.currentDriveSessionId())
            monitor.release()
            await("Released") { monitor.connectionState().state==CarConnectionState.RELEASED }
            val final=samples.get();monitor.startMonitoring();monitor.requestReconnect();Thread.sleep(1200)
            assertEquals(final,samples.get());assertEquals(CarConnectionState.RELEASED,monitor.connectionState().state)
            report.put("passed",true).put("speedCallbacks",samples.get()).put("connectedTransitions",
                states.count { it.state==CarConnectionState.CONNECTED }).put("rebindCycles",3)
                .put("driveSessionPreserved",true).put("runPreserved",true)
                .put("actualServiceDeath","not_tested_official_user_build")
        } finally {
            monitor.release();container.stopCollection()
            awaitStoredStop(container, context.filesDir)
            report.put("states",states.map { "${it.state}:${it.token}:${it.subscriptions}" }.joinToString(","))
            val directory=context.filesDir.resolve("diagnostics");directory.mkdirs()
            directory.resolve("carservice-reconnection-${System.currentTimeMillis()}.json").writeText(report.toString(),Charsets.UTF_8)
        }
    }

    private fun awaitStoredStop(container:AppContainer, root:java.io.File) {
        await("Actual FIFO session_stopped row") {
            root.resolve("events").listFiles().orEmpty().filter { it.name.startsWith(container.runId) }
                .any { file -> file.readLines(Charsets.UTF_8).any { line ->
                    runCatching { JSONObject(line).optString("kind")=="session_stopped" }.getOrDefault(false) } }
        }
    }

    @Test fun activityRebindRestartsExistingPleosSubscriptionsWithoutNewDrive() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val container=(context.applicationContext as GleoApplication).container
        assertNull("Do not replace an existing collection",container.driveSessions.currentDriveSessionId())
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor=MainActivity::class.java.getDeclaredField("aaosMonitor").apply { isAccessible=true }.get(activity) as AAOSVehicleMonitor
        val handler=MainActivity::class.java.getDeclaredField("vehicleSdkHandler").apply { isAccessible=true }.get(activity) as Handler
        val source=MainActivity::class.java.getDeclaredField("vehicleEventSource").apply { isAccessible=true }.get(activity)
        fun sdk():Pair<Any?,Int> {
            val latch=CountDownLatch(1);var result:Pair<Any?,Int>?=null
            handler.post {
                try {
                    val vehicle=source.javaClass.getDeclaredField("vehicle").apply { isAccessible=true }.get(source)
                    val registrations=source.javaClass.getDeclaredField("unregisters").apply { isAccessible=true }.get(source) as List<*>
                    result=vehicle to registrations.size
                } finally { latch.countDown() }
            }
            assertTrue("Existing SDK worker completes",latch.await(45,TimeUnit.SECONDS));return result!!
        }
        try {
            await("Activity collection and AAOS ready") { container.driveSessions.currentDriveSessionId()!=null &&
                monitor.connectionState().state==CarConnectionState.CONNECTED }
            val drive=container.driveSessions.currentDriveSessionId();val run=container.runId
            val before=sdk();assertNotNull(before.first);assertTrue(before.second>0)
            val token=monitor.connectionState().token;monitor.requestReconnect()
            await("Activity AAOS rebound") { monitor.connectionState().state==CarConnectionState.CONNECTED && monitor.connectionState().token!=token }
            var after=sdk()
            await("Existing Pleos SDK stop/start completes") { after=sdk();after.first!=null && after.first!==before.first }
            assertEquals("No duplicate typed SDK registrations",before.second,after.second)
            assertEquals(drive,container.driveSessions.currentDriveSessionId());assertEquals(run,container.runId)
        } finally {
            i.runOnMainSync { activity.finish() }
            await("Activity destroy releases AAOS retries") { monitor.connectionState().state==CarConnectionState.RELEASED }
            awaitStoredStop(container,context.filesDir)
        }
    }
}
