package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.LocalArchiveRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run in a fresh instrumentation process AFTER the actual Media3/Hearts test. Cache fixture only. */
class HeartsStep5RestartTest {
    @Test fun previouslySavedActualDriveSurvivesProcessRestart() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostic=File(context.filesDir,"diagnostics/car-v10-step5")
        val row=JSONObject(File(diagnostic,"result.json").readText())
        val key=row.getString("runId")+"/"+row.getString("driveSessionId")
        val roots=File(context.cacheDir,"hearts-step5-actual").listFiles().orEmpty().filter {it.isDirectory}
        val matches=roots.map {LocalArchiveRepository(it).load().filter {pin->pin.sourceDriveKey==key}}.filter {it.isNotEmpty()}
        assertEquals(1,matches.size);val pins=matches.single()
        assertEquals(row.getInt("pins"),pins.size);assertEquals(pins.size,pins.map{it.id}.toSet().size)
        assertTrue(pins.all {it.heartsReview!=null && it.place==null && it.heartsReview!!.playbackSessionIds.isNotEmpty()})
        File(diagnostic,"restart-result.json").writeText(JSONObject().put("savedPins",pins.size)
            .put("source","PREVIOUS_ACTUAL_MEDIA3_TEST_CACHE_ARCHIVE").put("freshProcessRepositoryRead",true).toString(2))
    }
}
