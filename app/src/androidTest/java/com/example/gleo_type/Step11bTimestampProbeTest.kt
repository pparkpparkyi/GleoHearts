package com.example.gleo_type

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.vehicle.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual callback clock experiments. Explicitly emulator-only; no user model or repositories. */
class Step11bTimestampProbeTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(s))
        .use{it.readBytes().toString(Charsets.UTF_8)}
    private fun await(label:String,f:()->Boolean){val end=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<end){if(f())return;Thread.sleep(50)};fail(label)}
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}

    @Test fun continuousInjectionOffsetFollowsDurationAndIsNotRecommended() {
        assertTrue(context.packageManager.hasSystemFeature("android.hardware.type.automotive"))
        assertTrue(android.os.Build.MODEL.contains("emulator",true))
        val root=File(context.cacheDir,"step11b-clock-${UUID.randomUUID()}").apply{mkdirs()}
        val c=AppContainer(root,"emulator_actual_callback_clock_experiment_not_physical")
        c.drivingContext.attach("probe")
        val evidence=JSONArray()
        val monitor=AAOSVehicleMonitor(context,onSpeedSample={speed,received,source->
            c.speedSamples.accept(speed,received,source)
        },onConnectionState={c.drivingContext.connection("probe",it)},onEvent={})
        fun rows()=File(root,"events").listFiles().orEmpty().filter{it.extension=="jsonl"}.flatMap{f->
            f.readLines().mapNotNull{runCatching{JSONObject(it)}.getOrNull()}}
        c.startCollection();val drive=c.driveSessions.currentDriveSessionId()!!
        try {
            monitor.startMonitoring()
            await("normal callback coverage"){c.drivingContext.current(drive).quality=="available"}
            for(duration in listOf(3,6)) {
                val before=c.speedTimestamps.latest?.sampleId
                shell("cmd car_service inject-continuous-events 291504647 5.0 -s 1 -d $duration")
                await("new future raw callback"){c.speedTimestamps.latest?.let{it.sampleId!=before && it.status=="FUTURE_SOURCE_TIMESTAMP"}==true}
                val observed=c.speedTimestamps.latest!!
                val offset= -observed.sourceAgeMs!!
                assertTrue("offset tracks specified duration, not ns/ms or UTC",offset in duration*1000L-500..duration*1000L+10)
                assertEquals("unknown",c.drivingContext.current(drive).quality)
                evidence.put(JSONObject().put("durationSeconds",duration).put("observedFutureOffsetMs",offset)
                    .put("status",observed.status).put("recommendationContext","unknown"))
                await("real current-time samples restore context"){c.drivingContext.current(drive).quality=="available"}
            }
        } finally {
            monitor.release();c.stopCollection()
            runBlocking{withTimeout(15000){c.eventPublisher.awaitSubmittedWrites()}}
            val records=rows();val rejected=records.filter{it.optString("sourceTimeQuality")=="FUTURE_SOURCE_TIMESTAMP"}
            assertTrue(rejected.isNotEmpty());assertTrue(rejected.all{!it.getBoolean("sourceTimeEligible") && it.isNull("normalizedSourceElapsedMs")})
            val files=File(root,"events").listFiles().orEmpty().filter{it.extension=="jsonl"}
            val proof=JSONObject().put("scope","ACTUAL_EMULATOR_CALLBACKS_NOT_REAL_CAR")
                .put("experiments",evidence).put("rejectedRawRowsPreserved",rejected.size)
                .put("relativeRoot",root.name).put("fileHashes",JSONObject(files.associate{it.name to hash(it)}))
            File(root,"proof.json").writeText(proof.toString(),Charsets.UTF_8)
            File(context.cacheDir,"step11b-clock-pointer.txt").writeText(root.name)
        }
    }

    @Test fun persistedClockEvidenceSurvivesInstrumentationProcessRestart() {
        // Must be invoked in a separate instrumentation after the experiment. Does not create a container.
        val root=File(context.cacheDir,File(context.cacheDir,"step11b-clock-pointer.txt").readText())
        assertTrue(root.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator))
        val proof=JSONObject(File(root,"proof.json").readText());val expected=proof.getJSONObject("fileHashes")
        for(name in expected.keys())assertEquals(expected.getString(name),hash(File(root,"events/$name")))
        assertEquals(2,proof.getJSONArray("experiments").length())
        assertTrue(proof.getInt("rejectedRawRowsPreserved")>0)
    }
}
