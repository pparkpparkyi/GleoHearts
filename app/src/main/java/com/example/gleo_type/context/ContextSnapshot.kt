package com.example.gleo_type.context

import com.example.gleo_type.event.core.*
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.recommendation.DrivingRecommendationInput
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class ContextFreshness { FRESH, STALE, MISSING, INVALID }
data class ContextField(val value: Any?, val source: String, val observedAt: String?,
    val observedElapsedMs: Long?, val freshness: ContextFreshness, val missingReason: String?,
    val environment: String, val eventId: String? = null, val origin: String = "OBSERVATION") {
    fun toMap(): Map<String, Any?> = linkedMapOf("value" to value, "source" to source,
        "observedAt" to observedAt, "observedElapsedMs" to observedElapsedMs,
        "freshness" to freshness.name, "missingReason" to missingReason, "environment" to environment,
        "eventId" to eventId, "origin" to origin, "clockBasis" to "app_elapsed_realtime")
}
data class ContextFreshnessPolicy(val locationMs: Long = 10_000, val destinationMs: Long = 15_000,
    val roadMs: Long = 15_000, val vehicleMs: Long = 2_500, val weatherMs: Long = 600_000,
    val version: String = "context_freshness_v1")
data class WeatherObservation(val appRunId: String, val driveSessionId: String?, val field: ContextField)
fun interface WeatherObservationPort { fun current(driveSessionId: String?): WeatherObservation? }

data class ContextSnapshot(val contextSnapshotId: String, val localUserId: String, val appRunId: String,
    val driveSessionId: String?, val voiceRequestId: String?, val createdAt: String, val capturedElapsedMs: Long,
    val environment: String, val currentLocation: Map<String, ContextField>, val destination: Map<String, ContextField>,
    val driving: Map<String, ContextField>, val road: Map<String, ContextField>,
    val timeWeather: Map<String, ContextField>, val voice: Map<String, ContextField>,
    val drivingInput: DrivingRecommendationInput, val freshnessPolicyVersion: String,
    val schemaVersion: Int = 1) {
    fun toMap(): Map<String, Any?> {
        fun fields(m: Map<String, ContextField>) = m.mapValues { it.value.toMap() }
        return linkedMapOf("schemaVersion" to schemaVersion, "contextSnapshotId" to contextSnapshotId,
            "localUserId" to localUserId, "appRunId" to appRunId, "driveSessionId" to driveSessionId,
            "voiceRequestId" to voiceRequestId, "createdAt" to createdAt, "capturedElapsedMs" to capturedElapsedMs,
            "speedTimeEvidence" to mapOf("sourceSampleIds" to drivingInput.sourceSampleIds,
                "joinClock" to "app_callback_elapsed_realtime", "sensorValidation" to "aaos_same_boot_ns_to_ms_v1",
                "quality" to drivingInput.quality, "fallbackReason" to drivingInput.fallbackReason),
            "environment" to environment, "freshnessPolicyVersion" to freshnessPolicyVersion,
            "currentLocation" to fields(currentLocation), "destination" to fields(destination),
            "driving" to fields(driving), "road" to fields(road), "timeWeather" to fields(timeWeather), "voice" to fields(voice),
            "rankingInputs" to listOf("existing_speed_state_if_usable", "existing_explicit_request_tags"),
            "storedOnly" to listOf("location", "destination", "other_vehicle_fields", "road", "time", "weather", "user_claims"))
    }
    /** An old snapshot cannot make stale speed fresh by retaining its original asOf time. */
    fun drivingFor(user: String, drive: String?, nowElapsedMs: Long, expectedAppRunId: String = appRunId): DrivingRecommendationInput {
        val input = drivingInput.copy(asOfElapsedMs = nowElapsedMs)
        val scopeValid = schemaVersion == 1 && expectedAppRunId == appRunId && user == localUserId && drive == driveSessionId &&
            input.runId == appRunId && input.driveSessionId == drive && nowElapsedMs >= capturedElapsedMs
        if (!scopeValid) return input.copy(quality = "unknown", fallbackReason = "snapshot_wrong_identity_or_clock")
        // Keep existing transport/missing-data diagnostics; the wrapper must not relabel them as identity failures.
        if (input.quality != "available" || input.fallbackReason != null) return input
        val fresh = input.latestObservedElapsedMs?.let { nowElapsedMs - it in 0..2500 } == true &&
            driving["speedState"]?.freshness == ContextFreshness.FRESH
        return if (fresh) input else input.copy(quality = "unknown", fallbackReason = "snapshot_stale_speed")
    }
}

/** Latest immutable DTOs only, bounded by domain/label. Never performs IO in SDK callbacks. */
class ContextObservationStore(private val activeDrive: () -> String?) {
    data class SpeedObservation(val drive: String, val speedKmh: Double, val elapsedMs: Long, val receivedAt: String)
    private val latest = linkedMapOf<String, EventDto>()
    private var speed: SpeedObservation? = null
    @Synchronized fun observeSpeed(drive: String, speedKmh: Double, elapsedMs: Long, receivedAt: String) {
        if (drive != activeDrive() || !speedKmh.isFinite() || (speed?.elapsedMs ?: -1) > elapsedMs) return
        speed = SpeedObservation(drive, speedKmh, elapsedMs, receivedAt)
    }
    @Synchronized fun readSpeed(drive: String?): SpeedObservation? = speed?.takeIf { drive != null && it.drive == drive && drive == activeDrive() }
    @Synchronized fun observe(event: EventDto) {
        if (event.driveSessionId == null || event.driveSessionId != activeDrive()) return
        if (event.payload !is LocationPayload && event.payload !is DestinationPayload &&
            event.payload !is TrafficPayload && event.payload !is VehiclePayload) return
        val key = if (event.payload is VehiclePayload) "VEHICLE:${event.label.code}" else event.domain.name
        val old = latest[key]
        if ((old?.observation?.receivedElapsedMs ?: -1) > (event.observation?.receivedElapsedMs ?: -1)) return
        latest[key] = event
    }
    @Synchronized fun read(drive: String?): Map<String, EventDto> =
        latest.filterValues { drive != null && it.driveSessionId == drive && drive == activeDrive() }.toMap()
    @Synchronized fun clear() { latest.clear(); speed = null }
}

/** Reuses existing observations/provider; creates no new location, route, weather or vehicle collectors. */
class ContextSnapshotProvider(val localUserId: String, val appRunId: String,
    private val observations: ContextObservationStore,
    private val speed: (String?) -> DrivingRecommendationInput,
    private val elapsed: () -> Long, private val environment: String,
    private val wall: () -> Instant = Instant::now, private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val policy: ContextFreshnessPolicy = ContextFreshnessPolicy(),
    private val weather: WeatherObservationPort = WeatherObservationPort { null }) {
    fun capture(drive: String?, request: VoiceCommand? = null): ContextSnapshot {
        if (request != null) require(request.localUserId == localUserId && request.appRunId == appRunId && request.driveSessionId == drive)
        val now = elapsed(); val at = wall(); val raw = observations.read(drive)
        fun missing(source: String, reason: String, origin: String = "OBSERVATION") =
            ContextField(null, source, null, null, ContextFreshness.MISSING, reason, environment, origin = origin)
        fun eventField(e: EventDto?, key: String, source: String, ttl: Long): ContextField {
            if (e == null) return missing(source, "not_observed_in_active_session")
            if ((e.payload as? DestinationPayload)?.destination?.cancelStatus == "true")
                return ContextField(null, source, e.observation?.receivedAt, e.observation?.receivedElapsedMs,
                    ContextFreshness.MISSING, "route_cancelled", environment, e.eventId)
            val destination = (e.payload as? DestinationPayload)?.destination
            if (destination != null && listOf(destination.name, destination.latitude, destination.longitude,
                    destination.remainingDistanceM, destination.remainingTimeSec, destination.totalDistanceM).all { it.isNullOrBlank() })
                return ContextField(null, source, e.observation?.receivedAt, e.observation?.receivedElapsedMs,
                    ContextFreshness.MISSING, "destination_unavailable", environment, e.eventId)
            val value = EventJsonSerializer().toJsonMap(e)[key]
            val ms = e.observation?.receivedElapsedMs
            val state = when {
                ms == null || ms < 0 || ms > now -> ContextFreshness.INVALID
                value == null -> ContextFreshness.MISSING
                e.payload is LocationPayload && (e.payload.location.latitude?.toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 } != true ||
                    e.payload.location.longitude?.toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 } != true) -> ContextFreshness.INVALID
                now - ms > ttl -> ContextFreshness.STALE
                else -> ContextFreshness.FRESH
            }
            return ContextField(value, source, e.observation?.receivedAt, ms, state,
                if (state == ContextFreshness.FRESH) null else state.name.lowercase(), environment, e.eventId)
        }
        val input = speed(drive)
        val speedObservation = observations.readSpeed(drive)
        val speedField = if (input.runId == appRunId && input.driveSessionId == drive && input.quality == "available" &&
            input.latestObservedElapsedMs?.let { now - it in 0..2500 } == true)
            ContextField(mapOf("averageSpeedKmh" to input.averageSpeedKmh, "motionState" to input.motionState,
                "coverage" to input.coverage, "observedDurationMs" to input.observedDurationMs,
                "connectionToken" to input.connectionToken), "DrivingContextProvider/${input.source}", input.latestReceivedAt,
                input.latestObservedElapsedMs, ContextFreshness.FRESH, null, input.environment,
                eventId = input.sourceSampleIds.lastOrNull(), origin = "DERIVED_OBSERVATION")
        else ContextField(null, "DrivingContextProvider/${input.source}", input.latestReceivedAt ?: speedObservation?.receivedAt,
            input.latestObservedElapsedMs ?: speedObservation?.elapsedMs,
            when (input.fallbackReason) {
                "stale_speed", "sensor_stale_source_timestamp" -> ContextFreshness.STALE
                "sensor_unknown_source_timestamp" -> ContextFreshness.MISSING
                else -> if (input.fallbackReason?.startsWith("sensor_") == true) ContextFreshness.INVALID else ContextFreshness.MISSING
            },
            input.fallbackReason ?: "speed_not_usable", input.environment, origin = "DERIVED_OBSERVATION")
        val driving = linkedMapOf("speedState" to speedField)
        driving["latestSpeedSample"] = if (speedObservation == null) missing("SpeedSampleRecorder/AAOS", "not_observed_in_active_session") else {
            val state = when {
                input.fallbackReason == "sensor_stale_source_timestamp" -> ContextFreshness.STALE
                input.fallbackReason == "sensor_unknown_source_timestamp" -> ContextFreshness.MISSING
                input.fallbackReason?.startsWith("sensor_") == true -> ContextFreshness.INVALID
                speedObservation.elapsedMs < 0 || speedObservation.elapsedMs > now || speedObservation.speedKmh < 0 -> ContextFreshness.INVALID
                now - speedObservation.elapsedMs > policy.vehicleMs -> ContextFreshness.STALE
                else -> ContextFreshness.FRESH
            }
            ContextField(mapOf("speedKmh" to speedObservation.speedKmh), "SpeedSampleRecorder/AAOS",
                speedObservation.receivedAt, speedObservation.elapsedMs, state,
                if (state == ContextFreshness.FRESH) null else state.name.lowercase(), environment)
        }
        for (label in listOf("BRAKE_CHANGED", "CRUISE_CONTROL_CHANGED", "CRUISE_TARGET_SPEED_CHANGED", "STEERING_ANGLE_CHANGED"))
            driving[label] = missing("VehicleEventSource/$label", "not_observed_in_active_session")
        driving["accelerationPattern"] = missing("acceleration_feature", "not_exposed_by_existing_recommendation_input")
        raw.filterKeys { it.startsWith("VEHICLE:") }.forEach { (key, e) ->
            driving[key.removePrefix("VEHICLE:")] = eventField(e, "vehicle", "VehicleEventSource/${e.label.code}", policy.vehicleMs)
        }
        val time = at.atZone(zone())
        val commandFreshness = when {
            request == null -> ContextFreshness.MISSING
            request.receivedElapsedMs < 0 || request.receivedElapsedMs > now -> ContextFreshness.INVALID
            now - request.receivedElapsedMs > 30_000 -> ContextFreshness.STALE
            else -> ContextFreshness.FRESH
        }
        val requestField = if (request == null) missing("VoiceCommand", "no_request", "COMMAND") else
            ContextField(mapOf("intent" to request.intent.name, "text" to request.text,
                "musicIntent" to request.musicRequest?.intent?.name, "parserStatus" to request.musicRequest?.status),
                request.source.name, request.receivedAt, request.receivedElapsedMs, commandFreshness,
                if (commandFreshness == ContextFreshness.FRESH) null else "command_${commandFreshness.name.lowercase()}", environment, origin = "COMMAND")
        val assertions = if (request == null || request.userClaims.isEmpty()) missing("USER_STATEMENT", "no_claims", "USER_STATEMENT")
            else ContextField(request.userClaims.toMap(), "USER_STATEMENT", request.receivedAt, request.receivedElapsedMs,
                commandFreshness, if (commandFreshness == ContextFreshness.FRESH) null else "claim_${commandFreshness.name.lowercase()}", environment, origin = "USER_STATEMENT")
        val observedWeather = weather.current(drive)
        val weatherField = if (observedWeather == null) missing("WeatherGateway", "gateway_unconnected_or_unattributed") else {
            val f = observedWeather.field; val ms = f.observedElapsedMs
            when {
                observedWeather.appRunId != appRunId || observedWeather.driveSessionId != drive ||
                    f.origin != "API_OBSERVATION" || f.source == "USER_STATEMENT" || ms == null || ms < 0 || ms > now ->
                    f.copy(value = null, freshness = ContextFreshness.INVALID, missingReason = "untrusted_weather_provenance")
                f.value == null -> f.copy(freshness = ContextFreshness.MISSING, missingReason = "weather_value_missing")
                now - ms > policy.weatherMs -> f.copy(freshness = ContextFreshness.STALE, missingReason = "weather_expired")
                else -> f
            }
        }
        return ContextSnapshot(UUID.randomUUID().toString(), localUserId, appRunId, drive, request?.voiceRequestId,
            at.toString(), now, environment,
            mapOf("position" to eventField(raw["LOCATION"], "location", "Pleos_FusedLocation/LocationEventSource", policy.locationMs),
                "stayPlace" to missing("stay_place", "not_implemented"),
                "movementState" to if (speedField.freshness == ContextFreshness.FRESH) speedField.copy(value = input.motionState)
                    else missing("DrivingContextProvider", "speed_state_unavailable")),
            mapOf("navigation" to eventField(raw["DESTINATION"], "navigation", "Pleos_NaviHelper/NaviEventSource", policy.destinationMs),
                "tripPurpose" to missing("trip_purpose", "not_observed")), driving,
            mapOf("congestionSegments" to eventField(raw["TRAFFIC"], "trafficStatus", "Pleos_NaviHelper/NaviEventSource", policy.roadMs),
                "roadSurface" to missing("road_surface", "not_observed"), "routePath" to missing("route_path", "not_observed")),
            mapOf("deviceTime" to ContextField(mapOf("hour" to time.hour, "dayOfWeek" to time.dayOfWeek.name,
                "zoneId" to time.zone.id), "DEVICE_CLOCK", at.toString(), now, ContextFreshness.FRESH, null, environment),
                "weather" to weatherField),
            mapOf("command" to requestField, "userClaims" to assertions), input, policy.version)
    }
}
