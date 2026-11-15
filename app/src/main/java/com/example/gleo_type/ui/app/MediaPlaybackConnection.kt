package com.example.gleo_type.ui.app

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.gleo_type.AppContainer
import com.example.gleo_type.music.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlaybackUi(val connected: Boolean = false, val track: Track? = null,
    val playing: Boolean = false, val position: Long = 0, val duration: Long? = null,
    val buffering: Boolean = false, val artwork: ByteArray? = null, val error: String? = null,
    val wantsPlay: Boolean = false, val playbackSessionId: String? = null,
    val canPlay: Boolean = true, val canPrevious: Boolean = true, val canNext: Boolean = true,
    val catalogReady: Boolean = true, val catalogObservedAt: String? = null, val catalogError: String? = null,
    val observedElapsedMs:Long?=null)
interface PlaybackPort {
    val state: StateFlow<PlaybackUi>
    val catalog: StateFlow<List<Track>>
    fun toggle()
    fun play(track: Track)
    fun playRecommended(track:Track,run:RecommendationRun) { play(track) }
    fun playTicket(ticket:com.example.gleo_type.context.PlaybackTicket) {error("Causal playback unavailable")}
    fun rememberCurrent(done:(CarV10SaveResult)->Unit) {done(CarV10SaveResult(null,"현재 재생 저장이 연결되지 않았습니다"))}
    fun likeObserved(adapter:com.example.gleo_type.recommendation.RecommendationModelAdapter,music:String,playback:String,drive:String?):Boolean=false
    fun next()
    fun previous()
    fun seek(position: Long)
    fun retryCatalog() {}
}

/** Browser only discovers the existing service catalog. UI commands use existing evidence/Player. */
class MediaPlaybackConnection(private val context: Context, private val container: AppContainer,
    sessionComponent:ComponentName=ComponentName(context,MusicPlaybackService::class.java)) : PlaybackPort, AutoCloseable {
    override val state = MutableStateFlow(PlaybackUi(catalogReady = false))
    override val catalog = MutableStateFlow<List<Track>>(emptyList())
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var catalogGeneration = 0
    private var browser: MediaBrowser? = null
    private val future = MediaBrowser.Builder(context,
        SessionToken(context, sessionComponent)).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }
    private val pulse = object : Runnable {
        override fun run() { if (!closed) { update(); handler.postDelayed(this, 500) } }
    }
    init {
        future.addListener({
            if (!closed) runCatching {
                val b = future.get(); browser = b; b.addListener(listener)
                retryCatalog()
                handler.post(pulse)
            }.onFailure { failed() }
        }, context.mainExecutor)
    }
    override fun retryCatalog() {
        val b = browser ?: run { failed(); return }
        val generation = ++catalogGeneration
        state.value = state.value.copy(catalogReady = false, catalogError = null)
        val root = b.getLibraryRoot(null)
        root.addListener({ if (!closed && generation == catalogGeneration) runCatching {
            val id = root.get().value?.mediaId ?: error("No library root")
            fun loadPage(page: Int, accumulated: List<MediaItem>) {
            val items = b.getChildren(id, page, 100, null)
            items.addListener({ if (!closed && generation == catalogGeneration) runCatching {
                val values = items.get().value ?: error("Catalog unavailable")
                val combined = (accumulated + values).distinctBy { it.mediaId }
                if (values.size == 100) {
                    check(page < 100 && combined.size > accumulated.size) { "Catalog pagination did not advance" }
                    loadPage(page + 1, combined)
                    return@runCatching
                }
                catalog.value = combined.map { Track(it.mediaId, it.mediaMetadata.title?.toString() ?: it.mediaId,
                    it.mediaMetadata.artist?.toString() ?: "아티스트 미제공") }
                state.value = state.value.copy(catalogReady = true, catalogError = null,
                    catalogObservedAt = java.time.Instant.now().toString())
            }.onFailure { catalogFailed() } }, context.mainExecutor)
            }
            loadPage(0, emptyList())
        }.onFailure { catalogFailed() } }, context.mainExecutor)
    }
    private fun catalogFailed() { state.value = state.value.copy(catalogReady = true, catalogError = "음악 목록을 읽지 못했습니다") }
    private fun failed() { state.value = state.value.copy(connected=false,playing=false,canPlay=false,canPrevious=false,canNext=false,
        error = "음악 서비스 연결 실패 · 앱을 다시 열어 주세요", catalogReady = true, catalogError = "음악 서비스 연결 실패") }
    private fun update() {
        val b = browser ?: return
        if(!b.isConnected){failed();return}
        val item = b.currentMediaItem
        state.value = PlaybackUi(true, item?.let { Track(it.mediaId,
            b.mediaMetadata.title?.toString() ?: it.mediaId, b.mediaMetadata.artist?.toString() ?: "아티스트 미제공") },
            b.isPlaying, b.currentPosition.coerceAtLeast(0), b.duration.takeIf { it > 0 && it != C.TIME_UNSET },
            b.playbackState == Player.STATE_BUFFERING, b.mediaMetadata.artworkData, b.playerError?.let { "재생 오류 (${it.errorCode})" }, b.playWhenReady,
            container.musicState.currentSnapshot()?.takeIf { it.musicId == item?.mediaId }?.playbackSessionId,
            b.mediaItemCount > 0 || catalog.value.isNotEmpty(), b.hasPreviousMediaItem(), b.hasNextMediaItem(),
            state.value.catalogReady, state.value.catalogObservedAt, state.value.catalogError,android.os.SystemClock.elapsedRealtime())
    }
    private fun command(name: String, action: (MediaBrowser) -> Unit) {
        playGeneration++
        val b = browser ?: return
        if(closed || !b.isConnected){failed();return}
        container.musicActions.mark(name, container.playbackSessions.currentId())
        runCatching { action(b) }.onFailure { failed() }
    }
    override fun toggle() = command(if (browser?.playWhenReady == true) "pause_button" else "play_button") { b ->
        if (b.playWhenReady) b.pause() else {
            if (b.mediaItemCount == 0) {
                if (catalog.value.isEmpty()) return@command
                b.setMediaItems(catalog.value.map { MediaItem.Builder().setMediaId(it.id).build() })
            }
            if (b.playbackState == Player.STATE_ENDED) b.seekToDefaultPosition()
            if (b.playbackState == Player.STATE_IDLE) b.prepare()
            b.play()
        }
    }
    override fun play(track: Track) = command("play_button") { b ->
        val index = (0 until b.mediaItemCount).firstOrNull { b.getMediaItemAt(it).mediaId == track.id }
        if (index != null) b.seekToDefaultPosition(index) else {
            val items = catalog.value
            val selected = items.indexOfFirst { it.id == track.id }
            if (selected < 0) return@command
            b.setMediaItems(items.map { MediaItem.Builder().setMediaId(it.id).build() }, selected, 0)
        }
        b.prepare(); b.play()
    }
    override fun playRecommended(track:Track,run:RecommendationRun) {
        if(run.contextEvidence==null || run.origin!=RecommendationOrigin.AI_MODEL){play(track);return}
        // One FIFO IO job per click; generation fencing rejects stale asynchronous commands.
        val request=++playGeneration
        UiWriteDispatch.submit({
            val ticket=container.uiPersonalization.prepare(run,track.id)
            handler.post {
                if(!closed && playGeneration==request && container.driveSessions.currentDriveSessionId()==run.driveId) {
                    if(ticket==null)play(track) else command("play_button") { b ->
                        val items=catalog.value;val selected=items.indexOfFirst{it.id==track.id}
                        if(selected<0)return@command
                        b.setMediaItems(items.map{if(it.id==track.id)com.example.gleo_type.context.CausalMediaItem.request(ticket)
                            else MediaItem.Builder().setMediaId(it.id).build()},selected,0);b.prepare();b.play()
                    }
                }
            }
        },{handler.post{state.value=state.value.copy(error="추천 재생 연결 실패 · 기존 재생 유지")}})
    }
    private var playGeneration=0L
    override fun playTicket(ticket:com.example.gleo_type.context.PlaybackTicket)=command("play_button") { b ->
        val tracks=catalog.value;val index=tracks.indexOfFirst{it.id==ticket.musicId};check(index>=0)
        b.setMediaItems(tracks.map{if(it.id==ticket.musicId)com.example.gleo_type.context.CausalMediaItem.request(ticket)
            else MediaItem.Builder().setMediaId(it.id).build()},index,0);b.prepare();b.play()
    }
    override fun rememberCurrent(done:(CarV10SaveResult)->Unit) {
        val s=state.value;val now=android.os.SystemClock.elapsedRealtime();val drive=container.driveSessions.currentDriveSessionId()
        val track=s.track;val pb=s.playbackSessionId
        if(closed||!s.connected||!s.playing||s.error!=null||drive==null||track==null||pb==null||s.observedElapsedMs?.let{now-it in 0..2000}!=true) {
            done(CarV10SaveResult(null,"신선한 실제 재생·수집 세션을 확인할 수 없습니다"));return
        }
        val current=container.contextSnapshots.capture(drive)
        val at=java.time.Instant.now().toString()
        UiWriteDispatch.submit({
            check(android.os.SystemClock.elapsedRealtime()-now in 0..5000)
            container.causalWritesBarrier().get(5,java.util.concurrent.TimeUnit.SECONDS)
            val rows=container.contextPipeline.ledger.records()
            val bound=rows.singleOrNull{it.kind==com.example.gleo_type.context.LedgerKind.PLAYBACK_BOUND && it.ids.localUserId==container.localIdentity.id &&
                it.ids.appRunId==container.runId&&it.ids.driveSessionId==drive&&it.ids.playbackSessionId==pb&&it.ids.musicId==track.id}
            val recommendationSnapshot=bound?.ids?.contextSnapshotId?.let{id->rows.singleOrNull{it.kind==com.example.gleo_type.context.LedgerKind.CONTEXT_SNAPSHOT&&it.ids.contextSnapshotId==id}?.payload?.toString()}
            val position=current.currentLocation["position"]?.takeIf{it.freshness==com.example.gleo_type.context.ContextFreshness.FRESH}
            val map=position?.value as? Map<*,*>
            val place=validPlace(map?.get("latitude")?.toString()?.toDoubleOrNull(),map?.get("longitude")?.toString()?.toDoubleOrNull())
            val capture=RuntimeHeartCapture(java.util.UUID.nameUUIDFromBytes((container.runId+"/"+pb+"/runtime-heart").toByteArray()).toString(),pb,at,now,s.position,s.duration,
                current.contextSnapshotId,org.json.JSONObject(current.toMap()).toString(),bound?.ids?.recommendationId,recommendationSnapshot,current.environment,position?.source)
            val saved=container.runtimeArchives.saveRuntime(RuntimeHeartRequest(container.localIdentity.id,container.runId,drive,
                container.reviewSync.identity.deviceId(),track,capture,place))
            handler.post{done(CarV10SaveResult(saved.id,"Hearts에 보관했습니다 · 중복 생성 없음"))}
        },{handler.post{done(CarV10SaveResult(null,"Hearts 저장 실패 · 기존 기록 보존"))}})
    }
    override fun likeObserved(adapter:com.example.gleo_type.recommendation.RecommendationModelAdapter,music:String,playback:String,drive:String?):Boolean {
        val observed=state.value
        if(!observed.connected || observed.error!=null || observed.track?.id!=music || observed.playbackSessionId!=playback)return false
        container.causalWritesBarrier().get(5,java.util.concurrent.TimeUnit.SECONDS)
        return container.uiPersonalization.like(music,playback,drive,adapter)
    }
    override fun next() = command("next_button") { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    override fun previous() = command("previous_button") { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    override fun seek(position: Long) = command("seek_control") { it.seekTo(position.coerceAtLeast(0)) }
    override fun close() {
        closed = true; catalogGeneration++; handler.removeCallbacks(pulse)
        browser?.removeListener(listener)
        MediaController.releaseFuture(future)
        browser = null
    }
}
