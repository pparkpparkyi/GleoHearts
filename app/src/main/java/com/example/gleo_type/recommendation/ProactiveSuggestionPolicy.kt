package com.example.gleo_type.recommendation

import com.example.gleo_type.context.ContextSnapshot
import com.example.gleo_type.context.ContextFreshness

data class ProactiveEvidence(val userConfirmedDestinationRole:String?,val distinctObservedVisits:Int,
    val observedWeekdayMorningMatches:Int,val callerConfidence:Double?,val lastSuggestionElapsedMs:Long?,val rejectedUntilElapsedMs:Long?)
data class ProactiveDecision(val suggest:Boolean,val automaticPlayback:Boolean=false,val reason:String)
/** Explicit evidence gate for future UI triggers. No inferred workplace/weather, no synthetic voice. */
object ProactiveSuggestionPolicy {
 fun evaluate(snapshot:ContextSnapshot,now:Long,e:ProactiveEvidence):ProactiveDecision {
  if(now<snapshot.capturedElapsedMs||now-snapshot.capturedElapsedMs>2500)return ProactiveDecision(false,reason="stale_or_future_snapshot")
  if(snapshot.drivingInput.quality!="available"||snapshot.drivingInput.fallbackReason!=null)return ProactiveDecision(false,reason="unknown_vehicle")
  if(snapshot.destination.values.none{it.freshness==ContextFreshness.FRESH&&it.value!=null})return ProactiveDecision(false,reason="missing_destination")
  if(e.userConfirmedDestinationRole!="WORK"||e.distinctObservedVisits<3||e.observedWeekdayMorningMatches<3)return ProactiveDecision(false,reason="unconfirmed_or_insufficient_pattern")
  // Do not manufacture a confidence from a destination name/time; caller must supply validated evidence.
  if(e.callerConfidence?.let{it.isFinite()&&it in .8..1.0}!=true)return ProactiveDecision(false,reason="pattern_confidence_unavailable")
  if(e.rejectedUntilElapsedMs?.let{now<it}==true||e.lastSuggestionElapsedMs?.let{now-it !in 600000..Long.MAX_VALUE}==true)
   return ProactiveDecision(false,reason="cooldown_or_clock_inversion")
  return ProactiveDecision(true,reason="confirmed_repeated_pattern_suggestion_only_untrained_thresholds")
 }
}
