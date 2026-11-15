package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ArchiveProjectionTest {
    private val drive=DriveRecord("run/drive","drive","2026-11-10T23:50:00Z","2026-11-11T00:10:00Z",
        listOf("song"),1000.0,true,null,true)
    @Test fun confirmedSummaryHasStableIdentityAndDoesNotInventPlace() {
        val a=ArchiveProjection.pinFromDrive(drive,Track("song","Observed","Artist"),listOf("메모"),"한글","2026-11-12T00:00:00Z")
        val b=ArchiveProjection.pinFromDrive(drive,a.track,emptyList(),"changed","2026-11-13T00:00:00Z")
        assertEquals(a.id,b.id);assertEquals(drive.key,a.sourceDriveKey);assertNull(a.place)
        assertEquals(drive.startedAt,a.occurredAt)
    }
    @Test fun missingEndAndUnobservedMusicCannotBeArchivedAsConfirmedSummary() {
        for(d in listOf(drive.copy(endedAt=null),drive.copy(startedAt=null),drive.copy(endedAt="2020-01-01T00:00:00Z")))
            assertTrue(runCatching{ArchiveProjection.pinFromDrive(d,null,emptyList(),"","now")}.isFailure)
        assertTrue(runCatching{ArchiveProjection.pinFromDrive(drive,Track("other","",""),emptyList(),"","now")}.isFailure)
    }
    @Test fun noMusicAndNoLocationAreValidObservedEmptyStates() {
        val p=ArchiveProjection.pinFromDrive(drive.copy(tracks=emptyList(),hasListening=false,listeningWallMs=0.0),null,emptyList(),"","now")
        assertNull(p.track);assertNull(p.place)
    }
    @Test fun calendarUsesExplicitTimeZoneAndObservedDateNotSaveDate() {
        val p=ArchiveProjection.pinFromDrive(drive,null,emptyList(),"","2026-11-15T00:00:00Z")
        val h=HistorySnapshot(listOf(drive))
        assertEquals(1,ArchiveProjection.onDate(h,listOf(p),LocalDate.parse("2026-11-10"),ZoneId.of("UTC")).second.size)
        assertEquals(1,ArchiveProjection.onDate(h,listOf(p),LocalDate.parse("2026-11-11"),ZoneId.of("Asia/Seoul")).second.size)
        assertTrue(ArchiveProjection.onDate(h,listOf(p),LocalDate.parse("2026-11-15"),ZoneId.of("UTC")).second.isEmpty())
    }
    @Test fun exactRunIdentityAndDeletedPinsNeverMixCalendarResults() {
        val p=ArchiveProjection.pinFromDrive(drive,null,emptyList(),"","now")
        assertFalse(ArchiveProjection.linked(p,drive.copy(key="other/drive")))
        assertTrue(ArchiveProjection.onDate(HistorySnapshot(listOf(drive)),listOf(p.copy(deletedAt="now")),LocalDate.parse("2026-11-10"),ZoneId.of("UTC")).second.isEmpty())
    }
}
