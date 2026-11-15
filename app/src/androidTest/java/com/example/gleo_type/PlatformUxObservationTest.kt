package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/** Real platform UX observations; connection lifecycle below is explicitly a test double. */
class PlatformUxObservationTest {
    @Test fun observePlatformUxAndRequireNewPolicyAfterConnectionGeneration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val monitor=CarDrivingUxMonitor(context)
        try {
            val until=SystemClock.elapsedRealtime()+15000
            while(!monitor.state.value.known && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            var ux=monitor.state.value
            val report=JSONObject().put("environment",android.os.Build.FINGERPRINT).put("source",ux.source)
                .put("known",ux.known).put("requiresOptimization",ux.requiresOptimization).put("restrictions",ux.activeRestrictions)
                .put("connectionInput","SYNTHETIC_LIFECYCLE_REAL_UX_OBSERVATIONS")
            val dir=File(context.filesDir,"diagnostics/car-v10-step6").apply{mkdirs()};val file=File(dir,"platform-ux.json");file.writeText(report.toString(2))
            assumeTrue("Actual platform UX unavailable; not a passed safety scenario",ux.known)
            val gate=HeartsEditGate();val connection=CarConnectionSnapshot(CarConnectionState.CONNECTED,1)
            val boundary=SystemClock.elapsedRealtime();assertFalse(gate.allowed(ux,connection,boundary))
            while(monitor.state.value.observedElapsedMs?.let{it>boundary}!=true && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            ux=monitor.state.value
            assertTrue("New actual UX observation",ux.observedElapsedMs!!>boundary)
            assertEquals(ux.complexAllowed,gate.allowed(ux,connection,SystemClock.elapsedRealtime()))
            assertFalse(gate.allowed(ux,connection.copy(state=CarConnectionState.RETRY_WAIT),SystemClock.elapsedRealtime()))
            assertFalse(gate.allowed(ux,connection.copy(token=2),SystemClock.elapsedRealtime()))
            report.put("generationChecksPassed",true).put("platformAllowsComplexUi",ux.complexAllowed);file.writeText(report.toString(2))
        } finally {monitor.close()}
    }
}
