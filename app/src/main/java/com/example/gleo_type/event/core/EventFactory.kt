package com.example.gleo_type.event.core

import com.example.gleo_type.event.format.toEventTimestamp
import java.time.Clock
import java.util.UUID

/** 주행 세션 ID 공급자. 세션 관리 기능이 붙기 전까지는 null을 반환한다. */
fun interface DriveSessionProvider {
    fun currentDriveSessionId(): String?
}

/**
 * envelope 공통 값(eventId, timestamp, driveSessionId)을 채운다.
 * 테스트에서는 clock / idGenerator / sessionProvider를 주입해 결과를 고정한다.
 */
class EventFactory(
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val sessionProvider: DriveSessionProvider = DriveSessionProvider { null },
    private val elapsedRealtime: () -> Long? = { null },
    private val playbackSessionId: () -> String? = { null }
) {
    fun create(payload: EventPayload, observation: EventObservation? = null): EventDto {
        val observed = observation ?: EventObservation(
            clock.instant().toEventTimestamp(), elapsedRealtime(), when (payload) {
                is MusicPayload -> playbackSessionId()
                is VehiclePayload -> payload.music.playbackSessionId
                is LocationPayload -> payload.music.playbackSessionId
                is DestinationPayload -> payload.music.playbackSessionId
                is TrafficPayload -> payload.music.playbackSessionId
                is WaypointPayload -> payload.music.playbackSessionId
                is FavoritesPayload -> payload.music.playbackSessionId
            }
        )
        return EventDto(
            eventId = idGenerator(),
            timestamp = observed.receivedAt,
            driveSessionId = sessionProvider.currentDriveSessionId(),
            payload = payload,
            observation = observed
        )
    }
}
