package com.example.gleo_type.ui.app

import com.example.gleo_type.ui.gleohearts.*
import org.junit.Assert.*
import org.junit.Test

class V13LiveProjectionTest {
    private fun project(p:PlaybackUi)=V13LiveProjection.player(p,null,DrivingUx(),10000,"TEST_CATALOG · 모델 없음")
    @Test fun noCurrentTrackIsNotARecommendationMasqueradingAsPlayback(){
        val s=project(PlaybackUi());assertNull(s.currentTrack);assertEquals(PlaybackStatus.IDLE,s.playback)
        assertFalse(s.canPlay);assertFalse(s.canPin);assertNull(s.pinSaveStatus)
    }
    @Test fun observedPlayingAndCapabilitiesArePreserved(){
        val s=project(PlaybackUi(connected=true,track=Track("canonical","제목","아티스트"),playing=true,canNext=false))
        assertEquals("canonical",s.currentTrack?.musicId);assertEquals(PlaybackStatus.PLAYING,s.playback)
        assertTrue(s.canPlay);assertFalse(s.canNext);assertFalse(s.canPin)
    }
    @Test fun failureAndBufferingDoNotBecomePlaying(){
        assertEquals(PlaybackStatus.ERROR,project(PlaybackUi(playing=true,error="failure")).playback)
        assertEquals(PlaybackStatus.BUFFERING,project(PlaybackUi(playing=true,buffering=true)).playback)
    }
    @Test fun unknownVehicleIsNeverReportedAsParked(){
        val s=project(PlaybackUi());assertEquals("차량 상태 미확인",s.vehicleLabel);assertFalse(s.canEditReview)
    }
    @Test fun reviewRequiresFreshOfficialRestrictionsNotZeroSpeedOrHandheldPolicy(){
        assertFalse(V13LiveProjection.canEdit(DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION",1000),1000))
        val ux=DrivingUx(true,false,0,"CarUxRestrictionsManager",1000)
        assertTrue(V13LiveProjection.canEdit(ux,7000));assertFalse(V13LiveProjection.canEdit(ux,7001))
        assertFalse(V13LiveProjection.canEdit(ux,999));assertFalse(V13LiveProjection.canEdit(ux.copy(requiresOptimization=true),1000))
    }
    @Test fun reorderedVisualTabsKeepExistingPreferencesAndPageIdentity(){
        GleoTab.entries.forEach { assertEquals(it,V13LiveProjection.tab(V13LiveProjection.index(it))) }
        assertEquals(GleoTab.MUSIC,V13LiveProjection.tab(1));assertEquals(GleoTab.CALENDAR,V13LiveProjection.tab(2))
    }
}
