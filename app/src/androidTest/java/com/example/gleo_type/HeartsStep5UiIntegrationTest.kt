package com.example.gleo_type

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.gleo_type.ui.app.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Actual collection/Media3/journal, isolated Hearts directory, explicitly synthetic UX permission. */
class HeartsStep5UiIntegrationTest {
    @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
    @Test fun realEndedCollectionCardsSaveAndRestoreWithoutTouchingJournal() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val c=(context.applicationContext as GleoApplication).container
        var port:MediaPlaybackConnection?=null
        try {
            i.runOnMainSync {c.startCollection();port=MediaPlaybackConnection(context,c)}
            ui.waitUntil(45000){port!!.catalog.value.isNotEmpty() && port!!.state.value.connected}
            val id=c.driveSessions.currentDriveSessionId()!!
            i.runOnMainSync {port!!.play(port!!.catalog.value.first())}
            ui.waitUntil(45000){port!!.state.value.playing && port!!.state.value.position>1000 && port!!.state.value.playbackSessionId!=null}
            i.runOnMainSync {port!!.toggle();c.stopCollection()}
            runBlocking {c.eventPublisher.awaitSubmittedWrites()}
            val history=JsonlHistoryRepository(File(context.filesDir,"events"),c.localIdentity.id)
            val record=history.refresh().drives.single {it.id==id && it.key.startsWith(c.runId+"/")}
            assertNotNull(record.endedAt);assertTrue(record.playbacks.isNotEmpty());assertTrue(record.tracks.isNotEmpty())
            val journal=File(context.filesDir,"events").listFiles()!!.filter {it.extension=="jsonl"}.associate {it.name to it.readBytes()}
            val root=File(context.cacheDir,"hearts-step5-actual/"+UUID.randomUUID())
            val repo=LocalArchiveRepository(root);val saver=HeartsReviewSaver(repo,history,c.localIdentity.id,{port!!.catalog.value},{true})
            var calls=0
            val actions=object:CarV10Actions {
                override val remember:((String,List<String>,(CarV10SaveResult)->Unit)->Unit)={key,ids,done -> calls++;done(saver.save(key,ids))}
            }
            val restoration=StateRestorationTester(ui)
            restoration.setContent {CarV10Review(record,port!!.catalog.value,
                CarV10UiState(ux=DrivingUx(true,false,0,"SYNTHETIC_UX_PERMISSION"),sourceDisclosure="실제 Media3/JSONL · 편집 허용은 테스트 더블"),actions)}
            val diagnostic=File(context.filesDir,"diagnostics/car-v10-step5").apply {mkdirs()}
            ui.onNodeWithTag("car_v10_review").captureToImage().asAndroidBitmap().let { bitmap ->
                File(diagnostic,"actual-review.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            ui.onNodeWithTag("car_remember").performScrollTo().performClick()
            ui.onNodeWithTag("car_save_status").assertTextContains("gleo Hearts에 저장했습니다",substring=true)
            ui.onNodeWithTag("car_remember").assertIsNotEnabled();assertEquals(1,calls)
            val saved=LocalArchiveRepository(root).load();assertEquals(record.tracks.size,saved.size)
            assertTrue(saved.all {it.sourceDriveKey==record.key && it.place==record.place})
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("car_home").assertExists();assertEquals(1,calls)
            journal.forEach {(name,bytes)->assertArrayEquals(bytes,File(context.filesDir,"events/$name").readBytes().copyOf(bytes.size))}
            File(context.filesDir,"diagnostics/car-v10-step5").mkdirs()
            File(context.filesDir,"diagnostics/car-v10-step5/result.json").writeText(org.json.JSONObject()
                .put("runId",c.runId).put("driveSessionId",id).put("playbackSessions",record.playbacks.size)
                .put("pins",saved.size).put("source","REAL_MEDIA3_JSONL_SYNTHETIC_UX_PERMISSION")
                .put("journalPrefixPreserved",true).put("saved",true).put("preferenceLearning","NOT_CONNECTED").toString(2))
        } finally {i.runOnMainSync {port?.let {if(it.state.value.playing)it.toggle();it.close()};c.stopCollection()};runBlocking {c.eventPublisher.awaitSubmittedWrites()}}
    }
}


