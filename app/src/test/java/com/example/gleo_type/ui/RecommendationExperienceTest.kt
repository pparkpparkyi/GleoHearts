package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationExperienceTest {
    private val tracks = (1..5).map { Track("test_$it", "곡 $it", "테스트") }
    private fun run(scope: MusicScope = MusicScope.THREE, variant: Int = 0, saved: Set<String> = emptySet(), excluded: Set<String> = emptySet()) =
        LocalRecommendationBuilder.build(tracks, scope, saved, excluded, variant, "2026-11-10T00:00:00Z", "synthetic-drive")
    @Test fun localSuggestionsHaveActualProvenanceAndNeverClaimAiOrVehicleFeatures() {
        val result = run()
        assertEquals(RecommendationOrigin.TEST_CATALOG, result.origin)
        assertNull(result.modelVersion); assertEquals(3, result.musicIds.size)
        assertTrue(result.usedFeatures.all { it.observedAt != null })
        assertTrue(result.usedFeatures.none { it.name.contains("speed", true) })
        assertEquals("synthetic-drive", result.driveId)
    }
    @Test fun regenerationRotatesWithoutMutatingPriorResultsAndScopesAreExplicit() {
        val first = run(); val next = run(variant = 1)
        assertEquals(first.musicIds.drop(1) + first.musicIds.first(), next.musicIds)
        assertNotEquals(first.id, next.id); assertEquals(first.catalogFingerprint, next.catalogFingerprint)
        assertEquals(listOf("test_2"), run(MusicScope.SAVED, saved = setOf("test_2")).musicIds)
        assertTrue(run(MusicScope.SAVED).musicIds.isEmpty())
        assertFalse(run(MusicScope.ALL, excluded = setOf("test_3")).musicIds.contains("test_3"))
    }
    @Test fun duplicateIdsAreRejectedAndOnlyDestructiveOperationsRequireVerification() {
        assertTrue(runCatching { LocalRecommendationBuilder.build(tracks + tracks.first(), MusicScope.ALL, emptySet(), emptySet(), 0, null, null) }.isFailure)
        assertTrue(VerificationPolicy.requiresConfirmation(DataOperation.CLEAR_LISTENING_HISTORY))
        assertTrue(VerificationPolicy.requiresConfirmation(DataOperation.RESET_PROFILE))
        assertFalse(VerificationPolicy.requiresConfirmation(DataOperation.SKIP))
        assertFalse(VerificationPolicy.requiresConfirmation(DataOperation.PLAY))
        assertFalse(VerificationPolicy.requiresConfirmation(DataOperation.REORDER_TEST_LIST))
    }
}
