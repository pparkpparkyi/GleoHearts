package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class UiPolicyTest {
    @Test fun upwardDismissRequiresDistanceAndNeverDismissesDownward() {
        assertFalse(GesturePolicy.dismiss(-107f, 108f))
        assertTrue(GesturePolicy.dismiss(-108f, 108f))
        assertFalse(GesturePolicy.dismiss(400f, 108f))
    }
    @Test fun sheetMovesOneLevelAndBoundsBothDirections() {
        assertEquals(0, GesturePolicy.sheetLevel(0, 100f, 40f))
        assertEquals(1, GesturePolicy.sheetLevel(0, -100f, 40f))
        assertEquals(2, GesturePolicy.sheetLevel(2, -100f, 40f))
        assertEquals(1, GesturePolicy.sheetLevel(2, 100f, 40f))
        assertEquals(1, GesturePolicy.sheetLevel(1, -30f, 40f))
    }
    @Test fun unconnectedProvidersCannotInventData() {
        assertFalse(UnconnectedServices.available)
        assertTrue(LocalPreviewRecommendations().recommendations(emptyList()).isEmpty())
        assertNull(validPlace(null, null)); assertNull(validPlace(91.0, 0.0))
        assertNull(validPlace(Double.NaN, 10.0)); assertNotNull(validPlace(0.0, 0.0))
    }
    @Test fun summariesDoNotInventMissingTimesOrInferDistanceFromSpeed() {
        val missing = DriveRecord("run/drive", "drive", null, null, emptyList(), 0.0, false, null, false)
        assertNull(missing.date); assertNull(missing.durationMs)
        val real = missing.copy(startedAt = "2026-11-10T10:00:00Z", endedAt = "2026-11-10T10:01:30Z")
        assertEquals(90000L, real.durationMs)
        assertNull(real.copy(endedAt = "2026-11-09T10:00:00Z").durationMs)
    }
}
