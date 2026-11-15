package com.example.gleo_type.event.core

import java.util.UUID

/** App collection session, not an ignition/trip detector. */
class DriveSessionManager(private val newId: () -> String = { UUID.randomUUID().toString() }) : DriveSessionProvider {
    private var id: String? = null
    @Synchronized override fun currentDriveSessionId(): String? = id
    @Synchronized fun start(): String = id ?: newId().also { id = it }
    @Synchronized fun stop(): String? = id.also { id = null }
}
