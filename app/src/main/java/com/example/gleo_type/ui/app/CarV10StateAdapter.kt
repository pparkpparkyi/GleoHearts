package com.example.gleo_type.ui.app

import com.example.gleo_type.recommendation.ContextInputSource
import com.example.gleo_type.recommendation.DrivingRecommendationInput
import com.example.gleo_type.vehicle.CarConnectionSnapshot
import com.example.gleo_type.vehicle.CarConnectionState

data class CarV10VehicleState(val connection:CarConnectionSnapshot,val driveSessionId:String?,
    val context:DrivingRecommendationInput?,val available:Boolean,val reason:String) {
    val label:String get() {
        val transport=when(connection.state) {
            CarConnectionState.CONNECTED -> "차량 연결됨"
            CarConnectionState.CONNECTING -> "차량 연결 중"
            CarConnectionState.RETRY_WAIT -> "차량 재연결 대기 (${connection.attempt})"
            else -> "차량 연결 unavailable (${connection.state})"
        }
        return if(available) "$transport · 관측 평균 속도 ${java.lang.String.format(java.util.Locale.ROOT,"%.1f",context!!.averageSpeedKmh)} km/h"
        else "$transport · 차량 맥락 unknown ($reason)"
    }
}
/** Reads existing providers only. Does not own SDK, collection, subscriptions or sessions. */
class CarV10StateAdapter(private val connection:()->CarConnectionSnapshot,
    private val drive:()->String?,private val context:(String?)->DrivingRecommendationInput,private val clock:()->Long,
    private val runId:String) {
    fun snapshot():CarV10VehicleState {
        val before=connection();val id=drive();val c=context(id);val after=connection();val now=clock()
        val reason=when {
            before!=after || id!=drive() -> "state_changed_during_read"
            after.state!=CarConnectionState.CONNECTED -> "transport_unavailable"
            id==null || c.driveSessionId!=id || c.runId!=runId -> "inactive_or_wrong_drive"
            c.source!=ContextInputSource.LIVE_SDK -> "not_live_sdk"
            c.connectionToken!=after.token -> "connection_generation_mismatch"
            c.quality!="available" -> c.fallbackReason ?: "context_unknown"
            now-c.asOfElapsedMs !in 0..2500 || c.latestObservedElapsedMs?.let{now-it in 0..2500}!=true -> "stale_or_untrusted_time"
            c.averageSpeedKmh?.let{it.isFinite() && it>=0}!=true -> "speed_unavailable"
            c.observedDurationMs<6000 || !c.coverage.isFinite() || c.coverage !in .6..1.0 -> "insufficient_observation"
            else -> "available"
        }
        return CarV10VehicleState(after,id,c.takeIf{reason=="available"},reason=="available",reason)
    }
}
