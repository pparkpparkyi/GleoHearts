package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.time.ZoneId

class PinArchiveIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun dir()=File(context.cacheDir,"pin-integration-${UUID.randomUUID()}").apply{mkdirs()}
    @Test fun journalProjectionMissingRowsRepeatedStopAndDurableArchiveOperations() {
        val root=dir();val events=File(root,"events").apply{mkdirs()};val f=File(events,"synthetic.jsonl")
        fun row(kind:String,extra:JSONObject=JSONObject()) {
            extra.put("kind",kind).put("runId","synthetic").put("driveSessionId","drive")
                .put("localUserId","synthetic").put("occurredAt",if(kind=="session_stopped")"2026-11-11T00:10:00Z" else "2026-11-10T23:50:00Z")
                .put("testData",true)
            f.appendText(extra.toString()+"\n",Charsets.UTF_8)
        }
        row("session_started")
        val repo=JsonlHistoryRepository(events,"synthetic")
        assertTrue(repo.refresh().drives.single().qualityFlags.contains("missing_session_end"))
        for((pb,id) in listOf("p1" to "song1","p2" to "song2")) {
            row("listening_interval",JSONObject().put("playbackSessionId",pb).put("musicId",id).put("intervalIndex",1).put("creditedWallMs",1000))
            row("listening_summary",JSONObject().put("playbackSessionId",pb).put("musicId",id).put("totalListeningWallMs",1000)
                .put("uniqueListenedMediaMs",900).put("uniqueListeningRate",.01).put("confidence","incomplete_lower_bound"))
        }
        row("session_stopped");row("session_stopped")
        val contexts=File(root,"music-context-experiments").apply{mkdirs()}
        fun feature(run:String,pb:String)=JSONObject().put("featureVersion","1").put("runId",run).put("driveSessionId","drive")
            .put("playbackSessionId",pb).put("musicId","song1").put("contextQuality","available")
            .put("avgSpeedKmh",12).put("observationCoverage",.8).put("source","SYNTHETIC_TEST")
        File(contexts,"features.jsonl").writeText(feature("synthetic","p1").toString()+"\n"+feature("other_run","p2").toString()+"\n")
        val drive=repo.refresh().drives.single()
        assertEquals(2000.0,drive.listeningWallMs,0.0);assertEquals(2,drive.playbacks.size);assertNull(drive.place)
        assertEquals(12.0,drive.playbacks.first{it.playbackSessionId=="p1"}.vehicleContext!!.avgSpeedKmh!!,0.0)
        assertNull(drive.playbacks.first{it.playbackSessionId=="p2"}.vehicleContext)
        assertEquals(drive,JsonlHistoryRepository(events,"synthetic").refresh().drives.single())
        val archiveDir=File(root,"archive");val archive=LocalArchiveRepository(archiveDir)
        val pin=ArchiveProjection.pinFromDrive(drive,Track("song1","테스트","합성"),listOf("테스트"),"메모","2026-11-12T00:00:00Z")
        archive.save(pin);archive.save(pin);assertEquals(1,archive.load().size)
        archive.save(pin.copy(note="수정",tags=listOf("수정")))
        assertEquals("수정",LocalArchiveRepository(archiveDir).load().single().note)
        archive.delete(pin.id);archive.delete(pin.id);assertTrue(archive.load().isEmpty())
        assertEquals(1,LocalArchiveRepository(archiveDir).deleted().size)
        LocalArchiveRepository(archiveDir).restore(pin.id)
        assertEquals("수정",archive.load().single().note)
        val daily=ArchiveProjection.onDate(repo.refresh(),archive.load(),java.time.LocalDate.parse("2026-11-11"),ZoneId.of("Asia/Seoul"))
        assertEquals(1,daily.first.size);assertEquals(1,daily.second.size)
    }
    @Test fun actualPleosJournalCanBePinnedWithoutCreatingLocationOrRecomputingListening() {
        val c=(context.applicationContext as GleoApplication).container
        val history=JsonlHistoryRepository(File(context.filesDir,"events"),c.localIdentity.id).refresh()
        assertNull(history.error)
        val drive=history.drives.first { it.endedAt!=null && it.startedAt!=null && it.playbacks.size>=3 }
        assertTrue(drive.playbacks.any{it.creditedWallMs>0})
        val directory=File(dir(),"archive")
        val pin=ArchiveProjection.pinFromDrive(drive,null,listOf("검증"),"Pleos observed journal",java.time.Instant.now().toString())
        LocalArchiveRepository(directory).save(pin)
        assertEquals(drive.place,LocalArchiveRepository(directory).load().single().place)
        val report=File(context.filesDir,"diagnostics/pin-archive-restart-fixture.json")
        report.parentFile!!.mkdirs()
        // Pointer to this isolated test archive only. Never replace the user's archive.
        report.writeText(JSONObject().put("directory",directory.absolutePath).put("pinId",pin.id)
            .put("observedPlaybacks",drive.playbacks.size).put("emulator",drive.testData)
            .put("environment",android.os.Build.FINGERPRINT).toString(),Charsets.UTF_8)
    }
    @Test fun separateProcessRecoversArchiveAndDurableDeleteRestore() {
        val j=JSONObject(File(context.filesDir,"diagnostics/pin-archive-restart-fixture.json").readText())
        val repo=LocalArchiveRepository(File(j.getString("directory")))
        val id=j.getString("pinId");assertEquals(id,repo.load().single().id)
        repo.delete(id);assertTrue(repo.load().isEmpty());LocalArchiveRepository(File(j.getString("directory"))).restore(id)
        assertEquals(id,repo.load().single().id)
    }
}
