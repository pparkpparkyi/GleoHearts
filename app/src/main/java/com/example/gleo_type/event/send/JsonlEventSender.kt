package com.example.gleo_type.event.send

import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.json.JsonWriter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

/** Single owner per directory. The nested event retains the existing JSON schema. */
class JsonlEventSender(
    private val directory: File,
    private val serializer: EventJsonSerializer = EventJsonSerializer(),
    private val runId: String = UUID.randomUUID().toString(),
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxDirectoryBytes: Long = 256L * 1024 * 1024,
    private val now: () -> Instant = Instant::now,
    private val inputOrigin: String = "sdk_callback",
    private val environment: String = "unverified",
    private val localUserId: String? = null
) : EventSender {
    private var sequence = 0L
    private var part = 0

    override suspend fun send(event: EventDto): Result<Unit> = append(
        "event", event.driveSessionId, event.timestamp,
        when (event.domain.name) {
            "VEHICLE" -> if (event.label.code == "SPEED_CHANGED") "AAOS" else "PLEOS_VEHICLE"
            "MUSIC" -> "MEDIA3_LOCAL_SAMPLE"
            "LOCATION" -> "PLEOS_FUSED_LOCATION"
            else -> "PLEOS_NAVIHELPER"
        }, "processed", mapOf("event" to serializer.toJsonMap(event),
            "musicSource" to "MEDIA3_LOCAL_SAMPLE") + (event.observation?.fields() ?: emptyMap())
    )

    @Synchronized
    fun append(
        kind: String, sessionId: String?, occurredAt: String, source: String,
        stage: String, fields: Map<String, Any?> = emptyMap()
    ): Result<Unit> = runCatching {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val next = sequence + 1
        val record = linkedMapOf<String, Any?>(
            "recordVersion" to "1", "runId" to runId, "sequence" to next.toString(),
            "kind" to kind, "occurredAt" to occurredAt, "storedAt" to now().toString(),
            "driveSessionId" to sessionId, "source" to source, "stage" to stage,
            "localUserId" to localUserId, "accountUserId" to null,
            "identityKind" to if (localUserId == null) "unknown" else "installation_pseudonym",
            "inputOrigin" to if (source == "MEDIA3_LOCAL_SAMPLE") "local_sample_playback" else inputOrigin,
            "environment" to environment,
            "testData" to when {
                source == "MEDIA3_LOCAL_SAMPLE" -> "sample_media"
                inputOrigin == "mock" || environment == "emulator" || source == "TEST" -> "true"
                else -> "unknown"
            }
        )
        require(fields.keys.none { it in record }) { "Reserved journal key" }
        record.putAll(fields)
        val bytes = (JsonWriter.write(record) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) throw IOException("Record exceeds file limit")
        val used = directory.listFiles()?.filter { it.extension == "jsonl" }?.sumOf { it.length() }
            ?: throw IOException("Cannot list $directory")
        if (used + bytes.size > maxDirectoryBytes) throw IOException("Event storage full; export old runs")
        var target = File(directory, "$runId-${part.toString().padStart(4, '0')}.jsonl")
        if (target.length() + bytes.size > maxFileBytes) {
            part++
            target = File(directory, "$runId-${part.toString().padStart(4, '0')}.jsonl")
        }
        RandomAccessFile(target, "rw").use { file ->
            val originalLength = file.length()
            try {
                file.seek(originalLength)
                file.write(bytes)
                file.fd.sync()
            } catch (error: Exception) {
                runCatching { file.setLength(originalLength); file.fd.sync() }
                throw error
            }
        }
        sequence = next
    }
}
