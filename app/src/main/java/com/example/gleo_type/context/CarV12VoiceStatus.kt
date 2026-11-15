package com.example.gleo_type.context

enum class VoiceRuntimePhase { WAITING, RECEIVED, RECOMMENDING, PLAYBACK_CONFIRMED, CONTROL_FAILED, OFFICIAL_DISCONNECTED }
data class OfficialVoiceGate(val runtimeObserved:Boolean=false,val schemaApprovalVerified:Boolean=false,
    val callerVerified:Boolean=false) {val connected get()=runtimeObserved && schemaApprovalVerified && callerVerified}
data class CarV12VoiceStatus(val phase:VoiceRuntimePhase,val title:String,val figmaNodeId:String?,
    val official:Boolean,val allowContextDetails:Boolean)

/** Runtime-to-design mapping, not an OEM driving approval or a microphone/listening indicator. */
object CarV12VoiceStatusMapper {
    fun map(phase:VoiceRuntimePhase,gate:OfficialVoiceGate,source:CommandSource,
        parkedObserved:Boolean=false,uxAllowsDetails:Boolean=false,uxFresh:Boolean=false,
        isolatedPreview:Boolean=false):CarV12VoiceStatus {
        val official=gate.connected && source==CommandSource.OFFICIAL_GLEO_ACTION
        val actual=if(official || isolatedPreview)phase else VoiceRuntimePhase.OFFICIAL_DISCONNECTED
        val label=when(actual){
            VoiceRuntimePhase.WAITING->"음성 요청 대기"
            VoiceRuntimePhase.RECEIVED->"요청 수신"
            VoiceRuntimePhase.RECOMMENDING->"추천 중"
            VoiceRuntimePhase.PLAYBACK_CONFIRMED->"재생 확인"
            VoiceRuntimePhase.CONTROL_FAILED->"제어 실패"
            VoiceRuntimePhase.OFFICIAL_DISCONNECTED->"공식 음성 미연결"
        }
        val node=when(actual){
            VoiceRuntimePhase.RECEIVED->"311:25767"
            VoiceRuntimePhase.RECOMMENDING->"311:26047"
            VoiceRuntimePhase.PLAYBACK_CONFIRMED->"311:26233"
            VoiceRuntimePhase.CONTROL_FAILED,VoiceRuntimePhase.OFFICIAL_DISCONNECTED->"311:26326"
            VoiceRuntimePhase.WAITING->null // No Figma demo listening screen is treated as proof of host listening.
        }
        return CarV12VoiceStatus(actual,if(isolatedPreview && !official)"모의시험 · $label" else label,node,official,
            parkedObserved && uxAllowsDetails && uxFresh)
    }
}
