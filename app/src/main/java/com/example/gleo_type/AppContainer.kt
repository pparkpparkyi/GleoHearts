package com.example.gleo_type

import android.app.Application
import android.util.Log
import com.example.gleo_type.event.core.DriveSessionManager
import com.example.gleo_type.event.send.JsonlEventSender
import com.example.gleo_type.event.vehicle.SpeedSampleRecorder
import java.io.File
import java.time.Instant
import java.util.UUID
import com.example.gleo_type.realtime.RealtimePipeline
import com.example.gleo_type.event.music.PlaybackSessions
import com.example.gleo_type.event.music.MusicActionEvidence
import com.example.gleo_type.event.core.LocalUserIdentity
import com.example.gleo_type.event.music.ListeningTracker
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.send.EventPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 수동 DI 컨테이너. 앱 전체에서 하나만 존재한다.
 * EventSender 구현체 교체는 이 파일의 [eventSender] 한 줄에서만 한다.
 */
class AppContainer(filesDir: File, environment: String = "unverified", identityDir: File = File(filesDir, "identity")) {

    val serializer = EventJsonSerializer()
    val localIdentity = LocalUserIdentity(identityDir)

    // App collection session; not vehicle ignition.
    val driveSessions = DriveSessionManager()

    val playbackSessions = PlaybackSessions()
    val musicActions = MusicActionEvidence { android.os.SystemClock.elapsedRealtime() }
    val eventFactory = EventFactory(
        sessionProvider = driveSessions,
        elapsedRealtime = { android.os.SystemClock.elapsedRealtime() },
        playbackSessionId = playbackSessions::currentId
    )

    /** 현재 음악 재생 상태 (MUSIC 외 domain의 music 요약에 사용) */
    val musicState = MusicStateHolder()

    val runId: String = UUID.randomUUID().toString()
    val musicContext = com.example.gleo_type.realtime.MusicContextRuntime(runId, filesDir) {
        android.os.SystemClock.elapsedRealtime()
    }
    val realtime = RealtimePipeline(runId, { android.os.SystemClock.elapsedRealtime() }).apply {
        observationSink = musicContext::event
    }

    val speedTimestamps = com.example.gleo_type.event.vehicle.SpeedTimestampValidator()
    val drivingContext = com.example.gleo_type.recommendation.DrivingContextProvider(runId,
        driveSessions::currentDriveSessionId, realtime, { android.os.SystemClock.elapsedRealtime() },
        environment = environment, sensorTimeBlock = { speedTimestamps.blockingReason(android.os.SystemClock.elapsedRealtime()) })

    val contextObservations = com.example.gleo_type.context.ContextObservationStore(driveSessions::currentDriveSessionId)
    val contextSnapshots = com.example.gleo_type.context.ContextSnapshotProvider(localIdentity.id, runId,
        contextObservations, drivingContext::current, { android.os.SystemClock.elapsedRealtime() }, environment)
    val contextPipeline by lazy {
        com.example.gleo_type.context.ContextPipeline(localIdentity.id, runId, driveSessions::currentDriveSessionId,
            contextSnapshots, com.example.gleo_type.context.PipelineLedger(File(filesDir, "ai-pipeline/v1/ledger.jsonl")),
            { android.os.SystemClock.elapsedRealtime() })
    }
    val runtimeArchives by lazy { com.example.gleo_type.ui.app.LocalArchiveRepository(File(filesDir,"archive")) }
    private val causalIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun bindCausalPlayback(ticketId: String, musicId: String, playbackId: String, observedDrive: String?) = causalIo.submit {
        runCatching { contextPipeline.bindObservedPlayback(ticketId, musicId, playbackId, observedDrive) }
            .onFailure { Log.w("ContextPipeline", "[CAUSAL_BIND_REJECTED] ${it.javaClass.simpleName}") }
    }
    fun causalWritesBarrier(): java.util.concurrent.Future<*> = causalIo.submit { }
    val uiPersonalization by lazy { com.example.gleo_type.context.UiPersonalization(localIdentity.id,runId,
        driveSessions::currentDriveSessionId,contextPipeline) }

    // ★ 구현체 교체 지점: 서버가 정해지면 RemoteEventSender(endpoint, serializer)로 교체
    val eventSender = JsonlEventSender(File(filesDir, "events"), serializer, runId = runId, environment = environment,
        localUserId = localIdentity.id)
    val reviewSync by lazy { com.example.gleo_type.sync.DriveSyncPreparation(filesDir,localIdentity.id,runId) }
    @Volatile private var syncPreparation:kotlinx.coroutines.Job? = null
    suspend fun awaitSyncPreparation() { syncPreparation?.join() }

    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eventPublisher = EventPublisher(eventSender, sendScope) { event, error ->
        Log.w(
            "EventStorage",
            "[PUBLISH_FAILED] ${event.domain}.${event.label.code} id=${event.eventId}: ${error.message}"
        )
    }.apply { realtimeObserver = { event -> realtime.event(event); contextObservations.observe(event) } }
    private fun journal(kind: String, id: String?, time: String, source: String,
                        stage: String, fields: Map<String, Any?> = emptyMap()) {
        eventPublisher.submit(
            { eventSender.append(kind, id, time, source, stage, fields).getOrThrow() },
            { Log.e("EventStorage", "[STORE_FAILED] $kind session=$id", it) }
        )
    }

    val listening = ListeningTracker(write = { kind, drive, time, fields ->
        journal(kind, drive, time, "MEDIA3_LOCAL_SAMPLE", "listening_measurement", fields)
        runCatching { musicContext.listening(runId, drive, kind, fields, realtime.hasInputLoss()) }
            .onFailure { Log.e("MusicContext", "[HANDOFF_FAILED]", it) }
    })
    /** Registered only while the music service owns a live Player; accessed on its main thread. */
    var listeningSnapshotProvider: (() -> MusicSnapshot?)? = null

    fun observeListening(snapshot: MusicSnapshot?, reason: String,
                         elapsed: Long = android.os.SystemClock.elapsedRealtime(), time: String = Instant.now().toString(),
                         playerState: String? = null) {
        val drive = driveSessions.currentDriveSessionId() ?: return
        val sample = snapshot ?: return
        val playback = sample.playbackSessionId ?: return
        listening.observe(ListeningTracker.Sample(drive, playback, sample.musicId, elapsed, time,
            sample.currentPositionMs, sample.durationMs, sample.isPlaying, sample.playbackSpeed.toDouble(), reason,
            playerState = playerState))
    }

    private fun captureListeningBoundary(reason: String, elapsed: Long, time: String): Boolean {
        // Never read a Player from a worker thread or extrapolate a final endpoint from a stale summary.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false
        val snapshot = runCatching { listeningSnapshotProvider?.invoke() }.getOrNull() ?: return false
        observeListening(snapshot, reason, elapsed, time)
        return true
    }

    val speedSamples = SpeedSampleRecorder { mps, elapsed, vehicleTime ->
        val id = driveSessions.currentDriveSessionId()
        val music = musicState.currentSnapshot()
        val receivedAt = Instant.now().toString()
        val timeEvidence = speedTimestamps.observe(UUID.randomUUID().toString(), vehicleTime, elapsed)
        if (id != null && timeEvidence.eligible) contextObservations.observeSpeed(id, mps.toDouble() * 3.6, elapsed, receivedAt)
        if (id != null && timeEvidence.eligible) runCatching {
            realtime.speed(id, mps.toDouble() * 3.6, elapsed, receivedAt, music?.playbackSessionId, music?.musicId,
                timeEvidence.sampleId, timeEvidence.sourceElapsedMs)
        }.onFailure { Log.e("RealtimePipeline", "[SPEED_INPUT_FAILED]", it) }
        if (id != null) journal("speed_sample", id, receivedAt, "AAOS", "raw_and_converted",
            mapOf("rawSpeed" to mps.toString(), "rawUnit" to "m/s",
                "speedKmh" to (mps.toDouble() * 3.6).toString(), "convertedUnit" to "km/h",
                "receivedElapsedMs" to elapsed.toString(),
                "vehicleTimestampNanos" to vehicleTime?.toString(),
                "receivedAt" to receivedAt, "sourceOccurredAt" to null,
                "sourceOccurredElapsedMs" to vehicleTime?.div(1_000_000)?.toString(),
                "sourceClock" to "android_elapsed_realtime",
                "timeBasis" to "app_callback_observation",
                "playbackSessionId" to music?.playbackSessionId,
                "musicId" to music?.musicId,
                "currentPositionMs" to music?.currentPositionMs?.toString(),
                "isPlaying" to music?.isPlaying?.toString(),
                "playbackSpeed" to music?.playbackSpeed?.toString(),
                "positionBasis" to "estimated_from_last_player_snapshot") + timeEvidence.fields())
    }

    @Synchronized fun startCollection() {
        if (driveSessions.currentDriveSessionId() != null) return
        speedSamples.reset()
        speedTimestamps.reset()
        val id = driveSessions.start()
        contextObservations.clear()
        runCatching { realtime.start(id) }.onFailure { Log.e("RealtimePipeline", "[START_FAILED]", it) }
        journal("session_started", id, Instant.now().toString(), "APP", "lifecycle",
            mapOf("sessionBasis" to "app_collection", "userId" to null, "playbackSessionId" to null))
        captureListeningBoundary("collection_started", android.os.SystemClock.elapsedRealtime(), Instant.now().toString())
    }

    @Synchronized fun stopCollection() {
        val id = driveSessions.currentDriveSessionId() ?: return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val time = Instant.now().toString()
        val actual = captureListeningBoundary("collection_stopped", elapsed, time)
        listening.finishCollection(id, elapsed, time, actual)
        runCatching { realtime.stop(id) }.onFailure { Log.e("RealtimePipeline", "[STOP_FAILED]", it) }
        driveSessions.stop()
        contextObservations.clear()
        journal("session_stopped", id, Instant.now().toString(), "APP", "lifecycle")
        syncPreparation=sendScope.launch {
            runCatching { com.example.gleo_type.ui.app.UiWriteDispatch.barrier().get(10,java.util.concurrent.TimeUnit.SECONDS); eventPublisher.awaitSubmittedWrites(); causalWritesBarrier().get(5,java.util.concurrent.TimeUnit.SECONDS); reviewSync.prepare(id) }
                .onFailure { Log.w("ReviewSync","[LOCAL_PREPARATION_FAILED] ${it.javaClass.simpleName}") }
        }
    }
}

class GleoApplication : Application() {
    val container: AppContainer by lazy {
        val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
            android.os.Build.PRODUCT.startsWith("sdk_")
        AppContainer(filesDir, if (emulator) "emulator" else "device_unverified", noBackupFilesDir).also {
            it.musicContext.store.enabled = getSharedPreferences("gleo_experiments", MODE_PRIVATE)
                .getBoolean("music_context_storage_enabled", false)
        }
    }
}
