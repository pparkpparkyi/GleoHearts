
package com.example.gleo_type.music

import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.time.Instant
import com.example.gleo_type.event.core.EventObservation
import com.example.gleo_type.event.music.MusicTransitionClassifier
import com.example.gleo_type.event.format.toEventTimestamp
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.gleo_type.GleoApplication
import com.example.gleo_type.R
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.music.MusicEventMapper
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.music.RepeatModeValue
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.example.gleo_type.AppContainer
import java.io.File
import java.util.concurrent.Executors

open class MusicPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "GleoMusic"
        private const val EVENT_TAG = "GleoMusicEvent"
        private const val ROOT_ID = "gleo_root"
    }

    private lateinit var player: ExoPlayer
    private lateinit var librarySession: MediaLibrarySession
    private lateinit var songs: List<MediaItem>

    protected open val container: AppContainer by lazy {
        (application as GleoApplication).container
    }
    protected open fun audioAssetRoot(): File = AndroidAudioAssetCatalog.productionRoot(this)
    private val audioIo = Executors.newSingleThreadExecutor()
    private val catalogReady = SettableFuture.create<Boolean>()
    private var audioRegistry: AudioAssetRegistry? = null
    @Volatile private var destroyed = false

    private fun <T> withCatalog(block: () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        Futures.transform(catalogReady, { block() }, mainExecutor)

    private fun availableSongs(): List<MediaItem> = songs.filter {
        audioRegistry?.contains(it.mediaId) != true || audioRegistry?.isAvailable(it.mediaId) == true
    }

    private var currentSongId: String? = null
    private var hasStartedCurrentSong = false
    private var pauseReported = false
    private var bufferingReported = false
    private var completedCurrentSong = false
    private var previousIsPlaying = false
    private var stoppedCurrentSong = false
    private var callbackObservation: EventObservation? = null
    private var listeningEnabled = true
    private val listeningHandler = Handler(Looper.getMainLooper())
    private val listeningProvider: () -> MusicSnapshot? = { captureSnapshot() }
    private val listeningPulse = object : Runnable {
        override fun run() {
            if (!listeningEnabled) return
            if (container.driveSessions.currentDriveSessionId() != null && player.isPlaying) {
                val snapshot = captureSnapshot()
                container.observeListening(snapshot, "heartbeat", playerState = player.playbackState.toString())
                lastSnapshot = snapshot
            }
            listeningHandler.postDelayed(this, 1000)
        }
    }

    private fun observeListening(reason: String) {
        if (!listeningEnabled || ((completedCurrentSong || stoppedCurrentSong) && player.isPlaying)) return
        val snapshot = captureSnapshot() ?: return
        if (snapshot.musicId != currentSongId) return
        val observation = callbackObservation ?: observeCallback()
        container.observeListening(snapshot, reason, observation.receivedElapsedMs!!, observation.receivedAt,
            player.playbackState.toString())
    }

    // 마지막으로 확인한 음악 상태 (갱신 시 MusicStateHolder에도 반영)
    private var lastSnapshot: MusicSnapshot? = null
        set(value) {
            field = value
            container.musicState.update(value)
        }

    // 곡 이동 직전 위치 보존
    private var transitionOldPosition: Long? = null

    // ==========================================
    // 서비스 생성
    // ==========================================

    override fun onCreate() {
        super.onCreate()

        songs = emptyList()

        songs = songs + JamendoResearchCatalog.mediaItems(this)
        // No network or automatic import. Large-file hashes stay off the Player/main thread.
        audioIo.execute {
            val loaded = runCatching { AndroidAudioAssetCatalog.load(this, audioAssetRoot()) }
            mainExecutor.execute {
                if (!destroyed) {
                    audioRegistry = loaded.getOrNull()
                    loaded.getOrNull()?.let { songs = (songs + AndroidAudioAssetCatalog.mediaItems(it)).distinctBy { item -> item.mediaId } }
                    if (loaded.isFailure) Log.w(TAG, "[AUDIO_CATALOG_UNAVAILABLE] Existing catalog retained")
                    catalogReady.set(true)
                }
            }
        }

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        C.AUDIO_CONTENT_TYPE_MUSIC
                    )
                    .build(),
                true
            )
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false

        player.addListener(createPlayerListener())

        librarySession = MediaLibrarySession.Builder(
            this,
            player,
            MusicLibraryCallback()
        ).build()

        container.listeningSnapshotProvider = listeningProvider
        listeningHandler.postDelayed(listeningPulse, 1000)
        Log.i(
            TAG,
            "[SERVICE_CREATED] songs=${songs.size}"
        )
    }

    // ==========================================
    // Media3 이벤트 감지
    // ==========================================

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                observeCallback(); observeListening("is_playing_changed")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                observeCallback(); observeListening("playback_state_changed")
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                observeCallback(); observeListening("play_when_ready_changed:$reason")
            }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                observeCallback(); observeListening("playback_speed_changed")
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                observeCallback()
                container.listening.discontinuity(lastSnapshot?.playbackSessionId,
                    oldPosition.positionMs.coerceAtLeast(0), callbackObservation!!.receivedElapsedMs!!,
                    callbackObservation!!.receivedAt, reason.toString())
                val oldIndex = oldPosition.mediaItemIndex
                val newIndex = newPosition.mediaItemIndex

                // 다른 곡으로 이동하기 전 위치 보관
                if (oldIndex != newIndex || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    transitionOldPosition =
                        oldPosition.positionMs.coerceAtLeast(0L)
                }

                // 동일 곡 안에서 재생 위치 이동
                if (
                    reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldIndex == newIndex
                ) {
                    val from = oldPosition.positionMs
                        .coerceAtLeast(0L)

                    val to = newPosition.positionMs
                        .coerceAtLeast(0L)

                    if (completedCurrentSong || stoppedCurrentSong) {
                        container.playbackSessions.begin()
                        hasStartedCurrentSong = false
                        completedCurrentSong = false
                        stoppedCurrentSong = false
                    }
                    if (from != to) {
                        emitEvent(
                            label = MusicLabel.SEEKED,
                            snapshot = captureSnapshot(),
                            fromPositionMs = from,
                            toPositionMs = to,
                            transitionReason = "seek"
                        )
                    }
                }
                if (oldIndex == newIndex && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    observeListening("discontinuity_after:$reason")
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                observeCallback()
                val old = lastSnapshot
                val newId = mediaItem?.mediaId
                val reasonName = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
                    else -> "unknown"
                }
                if (old != null) {
                    val position = transitionOldPosition
                        ?: container.musicState.currentSnapshot()?.currentPositionMs
                        ?: old.currentPositionMs
                    val oldSnapshot = old.copy(currentPositionMs = position)
                    val action = if (reasonName == "seek") container.musicActions.consume(
                        setOf("next_button", "previous_button"), old.playbackSessionId
                    ) else null
                    val label = MusicTransitionClassifier.label(reasonName, action)
                    val basis = if (transitionOldPosition != null) "discontinuity_old_position"
                                else "estimated_last_snapshot"
                    if (label == MusicLabel.AUTO_TRANSITION) emitCompleted(oldSnapshot, reasonName, basis)
                    emitEvent(label, oldSnapshot, transitionReason = reasonName,
                        actionOrigin = action, positionBasis = basis)
                }
                container.listening.finishPlayback(old?.playbackSessionId,
                    callbackObservation!!.receivedElapsedMs!!, callbackObservation!!.receivedAt, "track_transition:$reasonName")
                transitionOldPosition = null

                // 새 곡 재생 상태 초기화
                if (newId == null) container.playbackSessions.clear() else container.playbackSessions.begin()
                val causalTicket = com.example.gleo_type.context.CausalMediaItem.ticket(mediaItem)
                if (newId != null && causalTicket != null) {
                    container.playbackSessions.currentId()?.let { playbackId ->
                        container.bindCausalPlayback(causalTicket, newId, playbackId, container.driveSessions.currentDriveSessionId())
                    }
                }
                currentSongId = newId
                stoppedCurrentSong = false
                hasStartedCurrentSong = false
                pauseReported = false
                bufferingReported = false
                completedCurrentSong = false
                previousIsPlaying = false

                lastSnapshot = captureSnapshot()
                observeListening("media_item_transition:$reasonName")

                Log.d(
                    TAG,
                    "[MUSIC_CHANGED] $newId reason=$reason"
                )
            }

            override fun onEvents(
                player: Player,
                events: Player.Events
            ) {
                observeCallback()
                handlePlaybackState(events)
                player.currentMediaItem?.mediaId?.let {
                    audioRegistry?.observeProgress(it, player.currentPosition, player.isPlaying)
                }

                // Neutral state observations also cover buffering recovery/audio suppression.
                // They carry no inferred user intent.
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                    emitEvent(MusicLabel.PLAYBACK_STATE_CHANGED, captureSnapshot(),
                        transitionReason = if (events.contains(Player.EVENT_IS_PLAYING_CHANGED))
                            "is_playing_changed" else "playback_parameters_changed")
                }

                // 반복 모드 변경
                if (
                    events.contains(
                        Player.EVENT_REPEAT_MODE_CHANGED
                    )
                ) {
                    emitEvent(
                        MusicLabel.REPEAT_MODE_CHANGED,
                        captureSnapshot()
                    )
                }

                // 셔플 모드 변경
                if (
                    events.contains(
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                    )
                ) {
                    emitEvent(
                        MusicLabel.SHUFFLE_MODE_CHANGED,
                        captureSnapshot()
                    )
                }

                lastSnapshot = captureSnapshot()
                observeListening("player_events")
                if ((player.playbackState == Player.STATE_ENDED && completedCurrentSong) ||
                    (player.playbackState == Player.STATE_IDLE && stoppedCurrentSong)) {
                    container.listening.finishPlayback(lastSnapshot?.playbackSessionId,
                        callbackObservation!!.receivedElapsedMs!!, callbackObservation!!.receivedAt,
                        if (player.playbackState == Player.STATE_ENDED) "natural_end" else "player_idle")
                }
            }

            override fun onPlayerError(
                error: PlaybackException
            ) {
                player.currentMediaItem?.mediaId?.let { audioRegistry?.markPlaybackError(it) }
                observeCallback()
                emitEvent(
                    label = MusicLabel.ERROR,
                    snapshot = captureSnapshot(),
                    errorCode = error.errorCode,
                    errorMessage = error.message
                )

                observeListening("player_error")
                container.listening.finishPlayback(container.playbackSessions.currentId(),
                    callbackObservation!!.receivedElapsedMs!!, callbackObservation!!.receivedAt, "player_error")
                Log.e(TAG, "[PLAYER_ERROR]", error)
            }
        }
    }

    // ==========================================
    // STARTED / RESUMED / PAUSED
    // BUFFERING / COMPLETED 판별
    // ==========================================

    private fun handlePlaybackState(events: Player.Events) {

        if ((completedCurrentSong || stoppedCurrentSong) && player.isPlaying) {
            container.playbackSessions.begin()
            hasStartedCurrentSong = false
            pauseReported = false
            completedCurrentSong = false
            stoppedCurrentSong = false
        }
        val snapshot = captureSnapshot() ?: return

        // 새로운 곡이 확인되면 상태 초기화
        if (currentSongId != snapshot.musicId) {
            currentSongId = snapshot.musicId
            hasStartedCurrentSong = false
            pauseReported = false
            bufferingReported = false
            completedCurrentSong = false
            previousIsPlaying = false
        }

        val state = player.playbackState
        val isPlaying = player.isPlaying

        // 재생 중 버퍼링
        if (
            state == Player.STATE_BUFFERING &&
            player.playWhenReady &&
            hasStartedCurrentSong &&
            !bufferingReported
        ) {
            emitEvent(
                MusicLabel.BUFFERING,
                snapshot
            )

            bufferingReported = true
        }

        if (state != Player.STATE_BUFFERING) {
            bufferingReported = false
        }

        // 새로운 곡 첫 재생
        if (isPlaying && !hasStartedCurrentSong) {
            hasStartedCurrentSong = true
            pauseReported = false
            completedCurrentSong = false

            emitEvent(
                MusicLabel.STARTED,
                snapshot
            )
        }

        // 일시정지 후 재개
        else if (isPlaying && pauseReported) {
            pauseReported = false

            emitEvent(
                MusicLabel.RESUMED,
                snapshot
            )
        }

        // 명시적인 일시정지
        if (
            (previousIsPlaying || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) &&
            hasStartedCurrentSong && !pauseReported &&
            !isPlaying &&
            !player.playWhenReady &&
            state != Player.STATE_ENDED && state != Player.STATE_IDLE
        ) {
            pauseReported = true

            emitEvent(
                MusicLabel.PAUSED,
                snapshot
            )
        }

        // 마지막 곡의 정상 종료
        if (
            state == Player.STATE_ENDED &&
            hasStartedCurrentSong &&
            !completedCurrentSong
        ) {
            emitCompleted(snapshot)
        }

        if (state == Player.STATE_IDLE && hasStartedCurrentSong &&
            !completedCurrentSong && !stoppedCurrentSong) {
            emitEvent(MusicLabel.STOPPED, snapshot, transitionReason = "player_idle")
            stoppedCurrentSong = true
        }
        previousIsPlaying = isPlaying
    }

    // ==========================================
    // 곡 정상 완료 (중복 방지)
    // ==========================================

    private fun emitCompleted(snapshot: MusicSnapshot, reason: String = "natural_end",
                              positionBasis: String = "callback_player_snapshot") {
        if (completedCurrentSong) {
            return
        }

        // 위치=duration, isPlaying=false 정규화는 MusicEventMapper가 처리
        emitEvent(
            MusicLabel.COMPLETED,
            snapshot,
            transitionReason = reason,
            positionBasis = positionBasis
        )

        completedCurrentSong = true
    }

    // ==========================================
    // 음악 상태 스냅샷
    // ==========================================

    private fun captureSnapshot(): MusicSnapshot? {
        val item = player.currentMediaItem ?: return null

        val rawDuration = player.duration

        val duration = if (
            rawDuration > 0L &&
            rawDuration != C.TIME_UNSET
        ) {
            rawDuration
        } else {
            null
        }

        return MusicSnapshot(
            musicId = item.mediaId,
            durationMs = duration,
            currentPositionMs =
                player.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs =
                player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatModeValue.ONE
                Player.REPEAT_MODE_ALL -> RepeatModeValue.ALL
                else -> RepeatModeValue.OFF
            },
            shuffleEnabled = player.shuffleModeEnabled,
            playbackSpeed = player.playbackParameters.speed,
            playbackSessionId = container.playbackSessions.currentId()
        )
    }

    // ==========================================
    // Mapper → 이벤트 DTO → EventSender
    // ==========================================

    private fun observeCallback(): EventObservation = EventObservation(
        Instant.now().toEventTimestamp(), SystemClock.elapsedRealtime()
    ).also { callbackObservation = it }

    private fun emitEvent(
        label: MusicLabel,
        snapshot: MusicSnapshot?,
        fromPositionMs: Long? = null,
        toPositionMs: Long? = null,
        errorCode: Int? = null,
        errorMessage: String? = null,
        transitionReason: String = "unknown",
        actionOrigin: String? = null,
        positionBasis: String = "callback_player_snapshot"
    ) {
        if (snapshot == null) {
            Log.w(
                TAG,
                "[EVENT_IGNORED] $label 음악 정보 없음"
            )
            return
        }

        val payload: EventPayload = when (label) {
            MusicLabel.SEEKED -> MusicEventMapper.seeked(
                snapshot,
                fromPositionMs ?: snapshot.currentPositionMs,
                toPositionMs ?: snapshot.currentPositionMs
            )

            MusicLabel.ERROR -> MusicEventMapper.error(
                snapshot,
                errorCode ?: 0,
                errorMessage
            )

            else -> MusicEventMapper.state(label, snapshot)
        }

        val actionNames = when (label) {
            MusicLabel.PAUSED -> setOf("pause_button")
            MusicLabel.STARTED, MusicLabel.RESUMED -> setOf("play_button")
            MusicLabel.STOPPED -> setOf("stop_button")
            else -> emptySet()
        }
        val origin = actionOrigin ?: container.musicActions.consume(actionNames, snapshot.playbackSessionId)
        val observed = (callbackObservation ?: observeCallback()).copy(
            playbackSessionId = snapshot.playbackSessionId,
            transitionReason = transitionReason,
            actionOrigin = origin ?: "unknown",
            positionBasis = positionBasis,
            observedPositionMs = snapshot.currentPositionMs,
            playbackSpeed = snapshot.playbackSpeed,
            observedIsPlaying = snapshot.isPlaying,
            observedDurationMs = snapshot.durationMs,
            observedStateBasis = if (positionBasis == "discontinuity_old_position") "old_item_before_transition" else "player_callback_snapshot"
        )
        val event = container.eventFactory.create(payload, observed)

        Log.d(EVENT_TAG, "[MUSIC_EVENT] ${event.label.code} ${event.eventId}")

        container.eventPublisher.publish(event)
    }

    // ==========================================
    // 음악 목록 구성
    // ==========================================

    private fun createSong(
        id: String,
        title: String,
        artist: String,
        rawId: Int
    ): MediaItem {

        val uri = Uri.parse(
            "android.resource://$packageName/$rawId"
        )

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun rootItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Gleo Music")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun searchSongs(query: String): List<MediaItem> {
        return songs.filter { item ->

            val title = item.mediaMetadata.title
                ?.toString().orEmpty()

            val artist = item.mediaMetadata.artist
                ?.toString().orEmpty()

            title.contains(query, ignoreCase = true) ||
                    artist.contains(query, ignoreCase = true)
        }
    }

    // ==========================================
    // MediaLibrarySession Callback
    // ==========================================

    private inner class MusicLibraryCallback :
        MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {

            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem(), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<
                LibraryResult<ImmutableList<MediaItem>>
                > {

            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(
                        LibraryResult.RESULT_ERROR_BAD_VALUE
                    )
                )
            }

            return withCatalog {
                if (page < 0 || pageSize <= 0) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                else {
                    val available = availableSongs()
                    val start = (page.toLong() * pageSize).coerceAtMost(available.size.toLong()).toInt()
                    val end = (start.toLong() + pageSize).coerceAtMost(available.size.toLong()).toInt()
                    LibraryResult.ofItemList(available.subList(start, end), params)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {

            return withCatalog {
            val item = if (mediaId == ROOT_ID) {
                rootItem()
            } else {
                availableSongs().find {
                    it.mediaId == mediaId
                }
            }

            if (item == null) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(item, null)
            }
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {

            return withCatalog {
            val count = searchSongs(query).count { audioRegistry?.contains(it.mediaId) != true || audioRegistry?.isAvailable(it.mediaId) == true }

            session.notifySearchResultChanged(
                browser,
                query,
                count,
                params
            )

            LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<
                LibraryResult<ImmutableList<MediaItem>>
                > {

            return withCatalog {
            val results = searchSongs(query).filter { audioRegistry?.contains(it.mediaId) != true || audioRegistry?.isAvailable(it.mediaId) == true }

            if (page < 0 || pageSize <= 0) return@withCatalog LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)

            val start = (page.toLong() * pageSize)
                .coerceAtMost(results.size.toLong())
                .toInt()

            val end = (start.toLong() + pageSize)
                .coerceAtMost(results.size.toLong())
                .toInt()

            LibraryResult.ofItemList(results.subList(start, end), params)
            }
        }

        // ID만 전달받은 음악을 실제 재생 URI로 변환
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {

            val future = SettableFuture.create<List<MediaItem>>()
            catalogReady.addListener({
                if (destroyed) {
                    future.setException(IllegalStateException("Service closed"))
                    return@addListener
                }
                val candidates = songs.associateBy { it.mediaId }
                val registry = audioRegistry
                audioIo.execute {
                    runCatching {
                        mediaItems.mapNotNull { requested ->
                            val item = candidates[requested.mediaId] ?: return@mapNotNull null
                            val resolved = if (registry?.contains(item.mediaId) != true) item else {
                                // Avoid re-reading the entire 2.88GiB queue on every play command.
                                val r = registry.resolve(item.mediaId, rehash = false)
                                if (r.status == AudioAssetRegistry.Status.AVAILABLE)
                                    AndroidAudioAssetCatalog.mediaItem(registry.descriptor(item.mediaId)!!, r.file!!)
                                else null
                            }
                            if (resolved == null) null else {
                                val ticket = com.example.gleo_type.context.CausalMediaItem.ticket(requested)
                                if (ticket == null) resolved
                                else if (container.contextPipeline.validateTicket(ticket, item.mediaId))
                                    com.example.gleo_type.context.CausalMediaItem.attach(resolved, ticket)
                                else null
                            }
                        }
                    }.onSuccess { future.set(it) }.onFailure { future.setException(it) }
                }
            }, mainExecutor)
            return future
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession {
        return librarySession
    }

    override fun onDestroy() {
        destroyed = true
        catalogReady.cancel(false)
        audioIo.shutdown()

        observeCallback()
        if (hasStartedCurrentSong && !completedCurrentSong && !stoppedCurrentSong) {
            emitEvent(MusicLabel.STOPPED, captureSnapshot(), transitionReason = "service_destroyed")
        }
        container.observeListening(captureSnapshot(), "service_destroyed",
            callbackObservation!!.receivedElapsedMs!!, callbackObservation!!.receivedAt)
        container.listening.finishPlayback(container.playbackSessions.currentId(),
            callbackObservation!!.receivedElapsedMs!!, callbackObservation!!.receivedAt, "service_destroyed")
        listeningEnabled = false
        listeningHandler.removeCallbacks(listeningPulse)
        if (container.listeningSnapshotProvider === listeningProvider) container.listeningSnapshotProvider = null
        librarySession.release()
        player.release()
        container.musicState.clear()
        container.playbackSessions.clear()

        Log.i(TAG, "[SERVICE_DESTROYED]")

        super.onDestroy()
    }
}
