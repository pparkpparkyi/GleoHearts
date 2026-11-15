package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class CarV10DraftTest {
    private fun drive(ids:List<String> = listOf("a","b","c"))=DriveRecord("run/drive","drive",
        "2026-11-11T00:00:00Z","2026-11-11T00:01:00Z",ids,0.0,false,null,true)
    @Test fun exactFigmaTokensKeepBrandSeparateFromMedia() {
        assertEquals(0xFF4781FF,CarV10Design.light.musicPrimary)
        assertEquals(CarV10Design.light.musicPrimary,CarV10Design.dark.musicPrimary)
        assertEquals(0xFF1D1E21,CarV10Design.colors(true).backgroundPrimary)
        assertEquals(0xFFF7F8FA,CarV10Design.colors(false).backgroundPrimary)
        assertNotEquals(CarV10Design.brandDeepBlue,CarV10Design.musicPrimary)
    }
    @Test fun releaseThresholdAndRepeatedActionsAffectDraftOnly() {
        val record=drive();val draft=CarV10ReviewDraft.fromDrive(record)
        assertFalse(draft.releaseUpward("a",-99f,100f,true))
        assertTrue(draft.releaseUpward("a",-100f,100f,true))
        assertFalse(draft.releaseUpward("a",-150f,100f,true))
        assertEquals(listOf("b","c"),draft.includedMusicIds);assertEquals("b",draft.selectedMusicId)
        assertEquals(listOf("a","b","c"),record.tracks);assertEquals(record.tracks,draft.observedMusicIds)
        assertTrue(draft.restore("a",true));assertFalse(draft.restore("a",true))
    }
    @Test fun reducedAndUnknownUiCannotEditAndInvalidDragsCannotExclude() {
        val draft=CarV10ReviewDraft.fromDrive(drive());val before=draft.snapshot()
        draft.select("b",false);draft.beginEditing(false)
        assertFalse(draft.releaseUpward("a",-200f,100f,false))
        assertFalse(draft.restore("a",false));assertEquals(before,draft.snapshot())
        for((delta,threshold) in listOf(Float.NaN to 100f,-200f to 0f,-200f to -1f,-200f to Float.NaN))
            assertFalse(draft.releaseUpward("a",delta,threshold,true))
        assertFalse(draft.releaseUpward("unobserved",-200f,100f,true))
    }
    @Test fun snapshotRestoresExactDriveSelectionAndExclusions() {
        val draft=CarV10ReviewDraft.fromDrive(drive());draft.select("c",true)
        draft.releaseUpward("a",-200f,100f,true)
        val restored=CarV10ReviewDraft.fromDrive(drive(),draft.snapshot())
        assertEquals(draft.snapshot(),restored.snapshot())
        assertTrue(runCatching { CarV10ReviewDraft.fromDrive(drive().copy(key="other/drive"),draft.snapshot()) }.isFailure)
    }
    @Test fun missingBoundaryAndUnobservedSnapshotAreRejected() {
        assertTrue(runCatching { CarV10ReviewDraft.fromDrive(drive().copy(endedAt=null)) }.isFailure)
        assertTrue(runCatching { CarV10ReviewDraft.fromDrive(drive(),CarV10ReviewSnapshot("run/drive",null,listOf("missing"),true)) }.isFailure)
    }
    @Test fun emptyDriveAndRememberNeverFabricatePersistenceSuccess() {
        val empty=CarV10ReviewDraft.fromDrive(drive(emptyList()))
        assertNull(empty.selectedMusicId);assertTrue(empty.includedMusicIds.isEmpty())
        assertEquals(CarV10RememberStatus.NOT_CONNECTED,empty.requestRemember(true))
        assertEquals(CarV10RememberStatus.RESTRICTED,empty.requestRemember(false))
    }
    @Test fun exclusionVisualFractionIsContinuousClampedAndFinite() {
        assertEquals(0f,CarV10ReviewDraft.exclusionFraction(1f,100f),0f)
        assertEquals(.5f,CarV10ReviewDraft.exclusionFraction(-50f,100f),0f)
        assertEquals(1f,CarV10ReviewDraft.exclusionFraction(-300f,100f),0f)
        assertEquals(0f,CarV10ReviewDraft.exclusionFraction(Float.NaN,100f),0f)
    }
}
