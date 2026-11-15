package com.example.gleo_type.realtime

import com.example.gleo_type.event.json.JsonWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Separate, opt-in, append-only experimental aggregates. No raw sample or audio writes. */
class ExperimentalMusicContextStore(private val directory: File, private val runId: String) {
    @Volatile var enabled = false
    val failures = AtomicLong()
    val written = AtomicLong()
    @Synchronized fun append(value: PlaybackContextFeatures) {
        if (!enabled) return
        try {
            check(directory.isDirectory || directory.mkdirs())
            // Bounded disk usage; never rotate by deleting previous research data.
            check(directory.listFiles().orEmpty().sumOf { it.length() } < 32L * 1024 * 1024)
            val bytes = (JsonWriter.write(value.fields + ("storedAt" to Instant.now().toString())) + "\n").toByteArray(Charsets.UTF_8)
            RandomAccessFile(File(directory, "$runId.jsonl"), "rw").use { file ->
                val length = file.length()
                try { file.seek(length); file.write(bytes); file.fd.sync() }
                catch (error: Exception) { file.setLength(length); throw error }
            }
            written.incrementAndGet()
        } catch (_: Exception) { failures.incrementAndGet() }
    }
}

/** Both callback producers only trySend. Single worker serializes aggregation and optional IO. */
class MusicContextRuntime(runId: String, filesDir: File, private val clockMs: () -> Long) {
    private sealed interface Input {
        data class Event(val value: RealtimeObservation, val loss: Boolean, val generation: Long) : Input
        data class Listening(val key: RealtimeSession, val kind: String, val fields: Map<String, Any?>,
            val generation: Long, val loss: Boolean) : Input
    }
    val store = ExperimentalMusicContextStore(File(filesDir, "music-context-experiments"), runId)
    val dropped = AtomicLong()
    val processingFailures = AtomicLong()
    private val inbox = Channel<Input>(2048)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completed = ArrayDeque<PlaybackContextFeatures>()
    private val aggregator = PlaybackContextAggregator(clockMs, { value ->
        synchronized(completed) {
            if (completed.size == 32) completed.removeFirst()
            completed.addLast(value)
        }
        store.append(value)
    })
    private var lastGeneration = 0L
    init { scope.launch {
        for (input in inbox) try {
            val generation = when (input) { is Input.Event -> input.generation; is Input.Listening -> input.generation }
            // START establishes a new scope; prior session's dropped messages cannot poison it.
            when (input) {
                is Input.Event -> {
                    if (input.value.kind != ObservationKind.START && (input.loss || generation != lastGeneration))
                        aggregator.markLoss("realtime_or_handoff_loss", input.value.session)
                    aggregator.input(input.value)
                }
                is Input.Listening -> {
                    if (input.loss || generation != lastGeneration) aggregator.markLoss("realtime_or_handoff_loss", input.key)
                    aggregator.listening(input.key, input.kind, input.fields)
                }
            }
            lastGeneration = generation
        } catch (_: Exception) { processingFailures.incrementAndGet(); aggregator.markLoss("processing_failure") }
    } }
    fun event(value: RealtimeObservation, loss: Boolean) {
        if (inbox.trySend(Input.Event(value, loss, dropped.get())).isFailure) dropped.incrementAndGet()
    }
    fun listening(runId: String, driveId: String?, kind: String, fields: Map<String, Any?>, inputLoss: Boolean = false) {
        if (driveId == null) return
        if (inbox.trySend(Input.Listening(RealtimeSession(runId, driveId), kind, fields.toMap(), dropped.get(), inputLoss)).isFailure)
            dropped.incrementAndGet()
    }
    fun results(): List<PlaybackContextFeatures> = synchronized(completed) { completed.toList() }
}
