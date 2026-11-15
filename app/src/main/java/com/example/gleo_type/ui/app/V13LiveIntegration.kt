package com.example.gleo_type.ui.app

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.gleo_type.ui.gleohearts.*
import kotlinx.coroutines.launch

/** Pure projection; never owns SDK, MediaBrowser, collection or repositories. */
object V13LiveProjection {
    fun tab(index: Int) = when(index) { 1 -> GleoTab.MUSIC; 2 -> GleoTab.CALENDAR; 3 -> GleoTab.PIN; 4 -> GleoTab.MY; else -> GleoTab.HOME }
    fun index(tab: GleoTab) = when(tab) { GleoTab.HOME -> 0; GleoTab.MUSIC -> 1; GleoTab.CALENDAR -> 2; GleoTab.PIN -> 3; GleoTab.MY -> 4 }
    fun canEdit(ux: DrivingUx, now: Long) = ux.complexAllowed && ux.source == "CarUxRestrictionsManager" &&
        ux.observedElapsedMs?.let { now - it in 0..6000 } == true
    fun player(p: PlaybackUi, vehicle: CarV10VehicleState?, ux: DrivingUx, now: Long, disclosure: String): GleoCarUiState =
        GleoCarUiState(currentTrack=p.track?.let { GleoTrack(it.id,it.title,it.artist,durationMillis=p.duration) },
            playback=when { p.error!=null -> PlaybackStatus.ERROR; p.buffering -> PlaybackStatus.BUFFERING;
                p.playing -> PlaybackStatus.PLAYING; p.track!=null && p.connected -> PlaybackStatus.PAUSED; else -> PlaybackStatus.IDLE },
            recommendationReason=disclosure, driveSessionId=vehicle?.driveSessionId,
            vehicleLabel=vehicle?.label ?: "차량 상태 미확인",
            canPlay=p.connected && p.canPlay, canPrevious=p.connected && p.canPrevious, canNext=p.connected && p.canNext,
            canPin=p.connected && p.playing && p.error==null && p.playbackSessionId!=null && vehicle?.driveSessionId!=null &&
                RuntimeHeartProjection.carTouchAllowed(ux,now),
            errorMessage=p.error)
}

/** Reuses the existing recommendation request/evidence closure and player. */
@Composable internal fun V13LiveCarPlayer(player: PlaybackPort, playback: PlaybackUi, vehicle: CarV10VehicleState?,
    ux: DrivingUx, disclosure: String, commands: CarV10Actions, suggestion: CarMusicCandidate?,
    recommendationEnabled: Boolean, artwork: ByteArray?, onReasons: () -> Unit, modifier: Modifier = Modifier) {
    var destination by remember { mutableStateOf(CarDestination.MUSIC) }
    var error by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val state=V13LiveProjection.player(playback,vehicle,ux,SystemClock.elapsedRealtime(),disclosure)
    val actions=object:GleoCarActions {
        override fun onPrevious(){if(state.canPrevious)commands.previous()}
        override fun onNext(){if(state.canNext)commands.next()}
        override fun onPlayPause(){if(state.canPlay)commands.toggle()}
        override fun onShowReasons()=onReasons()
        override fun onShowMusic(){destination=CarDestination.MUSIC;error=null}
        override fun onVoiceRequest(){error="공식 Gleo 음성 런타임 미연결 · 음성 수신으로 표시하지 않습니다";destination=CarDestination.ERROR}
        override fun onPinCurrent(){
            if(busy || !state.canPin)return
            busy=true;destination=CarDestination.SAVING
            player.rememberCurrent { result -> scope.launch {
                busy=false;receipt=result.persistedReference?.takeIf{it.isNotBlank()};error=result.message
                destination=if(receipt!=null)CarDestination.SAVED else CarDestination.ERROR
            } }
        }
        // Review remains owned by GleoScreen/HeartsReviewSaver, not this player.
        override fun onOpenReview()=Unit
        override fun onExcludeFromHearts(musicId:String)=Unit
        override fun onUndoExclude(musicId:String)=Unit
        override fun onRememberReviewedTracks(musicIds:List<String>)=Unit
    }
    val bitmap=remember(artwork){artwork?.let{runCatching{BitmapFactory.decodeByteArray(it,0,it.size)}.getOrNull()}}
    GleoCarRoot(state.copy(destination=destination,errorMessage=error ?: state.errorMessage,
        pinSaveStatus=if(receipt!=null)SyncStatus.LOCAL_ONLY else null,canPin=state.canPin&&!busy),actions,
        modifier.testTag("v13_live_car"),
        currentArtwork=bitmap?.let{b->{m -> Image(b.asImageBitmap(),"현재 음악 앨범 아트",m,contentScale=ContentScale.Fit)}},
        footer={
            TextButton(onClick=commands::playRecommendation,enabled=recommendationEnabled && suggestion?.playable==true,
                modifier=Modifier.heightIn(min=64.dp).testTag("v13_play_recommendation")) {
                Text(suggestion?.let{"추천 재생 · ${it.title}"} ?: "재생 가능한 추천 없음")
            }
            TextButton(onClick=actions::onVoiceRequest,modifier=Modifier.heightIn(min=64.dp)) {Text("Gleo 음성 · 미연결")}
        })
}

/** Keep the established data-rich screens, overlays and Photo Picker. V13 owns
 * the five-tab shell, while legacy indices/preferences remain compatible. */
@Composable internal fun V13LiveScaffold(mobile: Boolean, tab: Int, onTab: (Int)->Unit,
    containerColor: Color, snackbarHost: @Composable ()->Unit, bottomBar: @Composable ()->Unit,
    content: @Composable (PaddingValues)->Unit) {
    if(!mobile) Scaffold(containerColor=containerColor,snackbarHost=snackbarHost,bottomBar=bottomBar,content=content)
    else Scaffold(containerColor=containerColor,snackbarHost=snackbarHost) { padding ->
        GleoMobileFrame(V13LiveProjection.tab(tab),{onTab(V13LiveProjection.index(it))},
            Modifier.padding(padding).consumeWindowInsets(padding).testTag("v13_live_mobile")) { content(PaddingValues()) }
    }
}
