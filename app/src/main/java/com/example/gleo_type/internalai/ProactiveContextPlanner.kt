package com.example.gleo_type.internalai

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.ContextInputSource
import java.time.*

/** Place tokens are opaque, locally approved IDs. Never guess WORK from coordinates or a name. */
data class ConfirmedPlaceRole(val owner:String,val token:String,val role:String,val confirmedAtEpochMs:Long)
data class ObservedTrip(val owner:String,val runId:String,val driveId:String,val destinationToken:String,
    val arrivedAtEpochMs:Long,val source:String,val completed:Boolean)
data class CommutePattern(val owner:String,val destinationToken:String,val observedDays:Int,
    val matchingDays:Int,val consistency:Double,val eligible:Boolean,val reason:String,val evaluatedAtEpochMs:Long)
data class SuggestionReceipt(val owner:String,val shownAtEpochMs:Long,val rejectedUntilEpochMs:Long?=null)
data class ProactivePlan(val suggest:Boolean,val kind:String?,val reason:String,val automaticPlayback:Boolean=false)

/** Heuristic decision port only; no UI trigger, location collection or automatic playback. */
class ProactiveContextPlanner(private val minimumDays:Int=3,private val minimumConsistency:Double=.75,
    private val cooldownMs:Long=600000,private val historyAgeMs:Long=60L*24*60*60*1000) {
    init{require(minimumDays>=3&&minimumConsistency in 0.0..1.0&&cooldownMs>0&&historyAgeMs>0)}
    fun pattern(owner:String,place:ConfirmedPlaceRole,trips:List<ObservedTrip>,nowEpochMs:Long,zone:ZoneId):CommutePattern {
        require(trips.size<=10000)
        fun no(reason:String)=CommutePattern(owner,place.token,0,0,0.0,false,reason,nowEpochMs)
        if(place.owner!=owner||place.role!="WORK"||place.token.isBlank()||place.confirmedAtEpochMs !in 0..nowEpochMs)
            return no("workplace_not_user_confirmed")
        val eligible=trips.filter{it.owner==owner&&it.runId.isNotBlank()&&it.driveId.isNotBlank()&&it.completed&&
            it.source=="LIVE_SDK_DESTINATION_ARRIVAL"&&it.arrivedAtEpochMs in 0..nowEpochMs&&nowEpochMs-it.arrivedAtEpochMs<=historyAgeMs}
            .groupBy{it.runId to it.driveId}.values.mapNotNull{group->group.distinct().singleOrNull()}
            .map{it to Instant.ofEpochMilli(it.arrivedAtEpochMs).atZone(zone)}
            .filter{(_,t)->t.dayOfWeek !in setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)&&t.hour in 6..10}
        val days=eligible.map{it.second.toLocalDate()}.distinct().size
        val matches=eligible.filter{it.first.destinationToken==place.token}.map{it.second.toLocalDate()}.distinct().size
        val ratio=if(days==0)0.0 else matches.toDouble()/days
        val enough=matches>=minimumDays&&ratio>=minimumConsistency
        return CommutePattern(owner,place.token,days,matches,ratio,enough,
            if(enough)"confirmed_work_repeated_weekday_mornings_untrained_rule" else "insufficient_consistent_observed_days",nowEpochMs)
    }
    fun decide(owner:String,run:String,drive:String?,snapshot:ContextSnapshot,nowElapsedMs:Long,nowEpochMs:Long,
        zone:ZoneId,connected:Boolean,currentDestinationToken:String?,pattern:CommutePattern?,receipts:List<SuggestionReceipt>,allowReplay:Boolean=false):ProactivePlan {
        fun no(reason:String)=ProactivePlan(false,null,reason)
        if(snapshot.localUserId!=owner||snapshot.appRunId!=run||snapshot.driveSessionId!=drive||drive==null)return no("wrong_identity_or_session")
        if(nowElapsedMs<snapshot.capturedElapsedMs||nowElapsedMs-snapshot.capturedElapsedMs>2500)return no("stale_snapshot")
        val snapshotEpoch=runCatching{Instant.parse(snapshot.createdAt).toEpochMilli()}.getOrNull() ?: return no("untrusted_wall_clock")
        if(nowEpochMs<0||nowEpochMs<snapshotEpoch||nowEpochMs-snapshotEpoch>5000)return no("untrusted_wall_clock")
        val driving=snapshot.drivingFor(owner,drive,nowElapsedMs,run)
        if(driving.source==ContextInputSource.REPLAY&&!allowReplay)return no("replay_input_not_live")
        if(!connected||driving.quality!="available"||driving.fallbackReason!=null||
            driving.observedDurationMs<6000||!driving.coverage.isFinite()||driving.coverage !in .6..1.0||
            driving.averageSpeedKmh?.let{it.isFinite()&&it>=0}!=true)return no("vehicle_unavailable")
        val mine=receipts.filter{it.owner==owner}
        if(mine.any{it.shownAtEpochMs<0||it.shownAtEpochMs>nowEpochMs||it.rejectedUntilEpochMs?.let{until->until>nowEpochMs}==true})return no("rejected_or_clock_untrusted")
        if(mine.maxOfOrNull{it.shownAtEpochMs}?.let{nowEpochMs-it<cooldownMs}==true)return no("cooldown")
        fun fresh(f:ContextField,ttl:Long)=f.freshness==ContextFreshness.FRESH&&f.value!=null&&
            f.observedElapsedMs?.let{nowElapsedMs-it in 0..ttl}==true&&f.origin!="USER_STATEMENT"&&f.source!="USER_STATEMENT"
        val destinationFresh=snapshot.destination["navigation"]?.let{fresh(it,15000)}==true
        val weather=snapshot.timeWeather["weather"]
        val rain=weather?.let{fresh(it,600000)&&it.origin=="API_OBSERVATION"&&(it.value as? Map<*,*>)?.let{v->
            listOf("condition","conditionCode").any{key->v[key]?.toString()?.lowercase() in setOf("rain","rainy")}}==true}==true
        val time=Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val commute=pattern?.let{it.owner==owner&&it.eligible&&it.destinationToken==currentDestinationToken&&
            it.evaluatedAtEpochMs in 0..nowEpochMs&&nowEpochMs-it.evaluatedAtEpochMs<=24L*60*60*1000}==true&&
            destinationFresh&&time.dayOfWeek !in setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)&&time.hour in 6..10
        return when {
            commute&&rain->ProactivePlan(true,"RAIN_COMMUTE","confirmed_pattern_and_fresh_api_rain")
            commute->ProactivePlan(true,"COMMUTE","confirmed_pattern")
            rain->ProactivePlan(true,"RAIN_TRIP","fresh_api_rain_no_commute_claim")
            else->no("no_verified_trigger")
        }
    }
}
