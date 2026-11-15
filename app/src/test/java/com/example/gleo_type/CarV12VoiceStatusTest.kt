package com.example.gleo_type

import com.example.gleo_type.context.*
import org.junit.Assert.*
import org.junit.Test

class CarV12VoiceStatusTest {
    @Test fun noRuntimeOrApprovalNeverProducesPretendListeningState() {
        VoiceRuntimePhase.entries.forEach { phase->
            val s=CarV12VoiceStatusMapper.map(phase,OfficialVoiceGate(),CommandSource.OFFICIAL_GLEO_ACTION)
            assertEquals(VoiceRuntimePhase.OFFICIAL_DISCONNECTED,s.phase);assertFalse(s.official)
            assertFalse(s.title.contains("듣고"));assertFalse(s.allowContextDetails)
        }
    }
    @Test fun simulatedStatesAreMarkedAndDetailsFailClosedForUnknownOrMovingUx() {
        val s=CarV12VoiceStatusMapper.map(VoiceRuntimePhase.PLAYBACK_CONFIRMED,OfficialVoiceGate(),CommandSource.SIMULATED_GLEO_ACTION,
            parkedObserved=true,uxAllowsDetails=true,uxFresh=true,isolatedPreview=true)
        assertEquals("311:26233",s.figmaNodeId);assertTrue(s.title.startsWith("모의시험"));assertFalse(s.official);assertTrue(s.allowContextDetails)
        for(flags in listOf(Triple(false,true,true),Triple(true,false,true),Triple(true,true,false)))
            assertFalse(CarV12VoiceStatusMapper.map(VoiceRuntimePhase.WAITING,OfficialVoiceGate(),CommandSource.TEST_TEXT,
                flags.first,flags.second,flags.third).allowContextDetails)
    }
    @Test fun sixRequestedTextsAreParsedWithoutAsrOrFakeObservedWeather() {
        val cases=listOf("글레오, 잔잔한 음악 틀어줘." to CommandIntent.RECOMMEND_AND_PLAY,
            "글레오, 좀 더 신나는 노래로 바꿔줘." to CommandIntent.CHANGE_MOOD,
            "글레오, 다음 곡." to CommandIntent.NEXT,"글레오, 잠깐 멈춰줘." to CommandIntent.PAUSE,
            "글레오, 이 노래 좋아." to CommandIntent.LIKE,"글레오, 이 노래는 싫어." to CommandIntent.DISLIKE)
        cases.forEach{assertEquals(it.first,it.second,KoreanMusicIntentParser.parse(it.first).intent)}
    }
}
