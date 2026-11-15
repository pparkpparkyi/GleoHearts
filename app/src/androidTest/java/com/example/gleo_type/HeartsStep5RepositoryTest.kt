package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class HeartsStep5RepositoryTest {
    private val record=DriveRecord("SYNTHETIC/run","run","2026-11-11T23:59:00Z","2026-11-12T00:01:00Z",listOf("a","b"),1000.0,true,null,true,
        playbacks=listOf(PlaybackArchive("pb-a","a",1000.0,1000.0,.1,"observed",emptySet())),qualityFlags=setOf("location_unavailable"),dataSources=setOf("SYNTHETIC"))
    private fun directory()=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"hearts-step5/"+UUID.randomUUID()).apply{mkdirs()}
    @Test fun atomicSelectionRestoreEditAndDateUseExistingStore() {
        val dir=directory();val repo=LocalArchiveRepository(dir)
        val first=repo.saveReview(record,emptyList(),listOf("a","b"),"SYNTHETIC_LOCAL","Asia/Seoul","2026-11-12T01:00:00Z")
        assertEquals(2,first.size);assertTrue(first.all { it.place==null })
        repo.save(first.first().copy(note="한글 메모",tags=listOf("나의 태그")))
        val second=repo.saveReview(record,emptyList(),listOf("a"),"SYNTHETIC_LOCAL","Asia/Seoul","2026-11-12T02:00:00Z")
        assertEquals(first.first().id,second.single().id);assertEquals("한글 메모",second.single().note)
        var reopened=LocalArchiveRepository(dir)
        assertEquals(1,reopened.load().size);assertEquals(1,reopened.deleted().size)
        val pin=reopened.load().single();assertEquals(listOf("b"),pin.heartsReview!!.excludedMusicIds)
        assertEquals(listOf("pb-a"),pin.heartsReview!!.playbackSessionIds)
        assertEquals(1,ArchiveProjection.onDate(HistorySnapshot(listOf(record)),reopened.load(),LocalDate.parse("2026-11-12"),ZoneId.of("Asia/Seoul")).second.size)
        assertEquals(1,ArchiveProjection.onDate(HistorySnapshot(listOf(record)),reopened.load(),LocalDate.parse("2026-11-11"),ZoneId.of("UTC")).second.size)
        reopened.saveReview(record,emptyList(),listOf("a","b"),"SYNTHETIC_LOCAL","Asia/Seoul","2026-11-12T03:00:00Z")
        reopened=LocalArchiveRepository(dir);assertEquals(2,reopened.load().size);assertEquals(0,reopened.deleted().size)
        reopened.delete(pin.id);assertEquals(1,reopened.load().size);reopened.restore(pin.id);assertEquals(2,reopened.load().size)
    }
    @Test fun invalidAndCorruptInputsDoNotOverwriteOriginal() {
        val dir=directory();val repo=LocalArchiveRepository(dir)
        repo.saveReview(record,emptyList(),listOf("a"),"local","UTC","2026-11-12T01:00:00Z")
        val file=File(dir,"pins.json");val bytes=file.readBytes()
        for(ids in listOf(emptyList(),listOf("other"),listOf("a","a")))assertTrue(runCatching{repo.saveReview(record,emptyList(),ids,"local","UTC","2026-11-12T01:00:00Z")}.isFailure)
        assertTrue(runCatching{repo.saveReview(record.copy(endedAt=null),emptyList(),listOf("a"),"local","UTC","2026-11-12T01:00:00Z")}.isFailure)
        assertArrayEquals(bytes,file.readBytes())
        file.writeText("CORRUPT_SYNTHETIC_FIXTURE")
        assertTrue(runCatching{repo.saveReview(record,emptyList(),listOf("a"),"local","UTC","2026-11-12T01:00:00Z")}.isFailure)
        assertEquals("CORRUPT_SYNTHETIC_FIXTURE",file.readText())
    }
}
