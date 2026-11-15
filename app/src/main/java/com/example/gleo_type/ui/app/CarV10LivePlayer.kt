package com.example.gleo_type.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shares GleoApp's PlaybackPort. No new browser, SDK or telemetry subscriptions. */
@Composable fun CarV10LivePlayer(player:PlaybackPort,vehicle:CarV10StateAdapter,
    ux:DrivingUx,dark:Boolean,music:CarMusicRecommendationSource?=null,
    sourceDisclosure:String="로컬 CLAP · Media3 · Gleo 미연결 · 내부 연구용",onEndCollection:(()->Unit)?=null,
    contextSnapshotProvider:(()->com.example.gleo_type.context.ContextSnapshot)?=null,onExit:()->Unit) {
    val playback by player.state.collectAsState()
    var car by remember(vehicle){mutableStateOf<CarV10VehicleState?>(null)}
    val catalog by player.catalog.collectAsState()
    var recommendation by remember(music){mutableStateOf<CarMusicSnapshot?>(null)}
    var musicStatus by remember(music){mutableStateOf("추천 확인 중")}
    var recommendationReady by remember(music){mutableStateOf(false)}
    var pending by remember(music){mutableStateOf<Pair<CarMusicSnapshot,CarMusicCandidate>?>(null)}
    var pendingErrorBefore by remember(music){mutableStateOf<String?>(null)}
    var contextDetails by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var contextSnapshot by remember { mutableStateOf<com.example.gleo_type.context.ContextSnapshot?>(null) }
    LaunchedEffect(contextDetails,contextSnapshotProvider) {
        while(contextDetails && contextSnapshotProvider!=null) {
            contextSnapshot=withContext(Dispatchers.Default){contextSnapshotProvider()};delay(1000)
        }
    }
    LaunchedEffect(pending) {
        val requestPending=pending ?: return@LaunchedEffect
        delay(15000)
        if(pending===requestPending){music?.evidence(requestPending.first.run.id,requestPending.second,"unconfirmed",null)
            pending=null;musicStatus="재생 확인 미완료 · 기존 재생 유지"}
    }
    val request=music?.request()
    LaunchedEffect(music,catalog,car?.available,car?.reason,car?.connection?.token,car?.driveSessionId,car?.context?.motionState,
        request,if(request?.intent==com.example.gleo_type.recommendation.MusicRequestIntent.ALTERNATIVE)playback.track?.id else null,playback.catalogReady) {
        if(music!=null && playback.catalogReady && car!=null) {
            musicStatus="추천 확인 중";recommendationReady=false
            runCatching{withContext(Dispatchers.IO){music.refresh(catalog,car?.driveSessionId,
                "${car?.available}/${car?.reason}/${car?.connection?.token}/${car?.context?.motionState}",request,playback.track?.id,playback.catalogObservedAt)}}
                .onSuccess{recommendation=it;recommendationReady=true;musicStatus=if(it.run.origin==RecommendationOrigin.AI_MODEL)
                    if(it.playableRecommendations.isEmpty())"AI_MODEL · 재생 가능한 추천 없음" else if(it.run.drivingDecision?.applied==true)"AI_MODEL · 실험적 차량 맥락 보정" else "AI_MODEL · 현재 차량 정보 없이 취향 기반 추천"
                    else "TEST_CATALOG · 실제 AI 추천 미준비"}
                .onFailure{if(it is kotlinx.coroutines.CancellationException)throw it;musicStatus="추천 읽기 오류 · 이전 목록 유지"}
        }
    }
    val current=recommendation?.candidates?.firstOrNull{it.mediaId==playback.track?.id}
    var features by remember(playback.track?.id){mutableStateOf<CarAcousticFeatures?>(null)}
    var approvedArt by remember(playback.track?.id){mutableStateOf<ByteArray?>(null)}
    LaunchedEffect(music,current?.trackId,playback.track?.id) {
        val result=withContext(Dispatchers.IO){music?.presentation?.let{it.feature(current) to it.artwork(current)}}
        features=result?.first;approvedArt=result?.second
    }
    LaunchedEffect(playback.track?.id,playback.playing,playback.playbackSessionId,playback.error,pending) {
        val p=pending
        if(p!=null && playback.error!=null && playback.error!=pendingErrorBefore){music?.evidence(p.first.run.id,p.second,"request_failed",
            playback.playbackSessionId.takeIf{playback.track?.id==p.second.mediaId});pending=null}
        else if(p!=null && playback.track?.id==p.second.mediaId && playback.playing && playback.playbackSessionId!=null){
            music?.evidence(p.first.run.id,p.second,"observed_started",playback.playbackSessionId);pending=null
        }
    }
    val owner=LocalView.current.findViewTreeLifecycleOwner()
    LaunchedEffect(vehicle,owner) {
        owner?.lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) { try { while(true) {
            car=withContext(Dispatchers.Default){runCatching{vehicle.snapshot()}.getOrNull()}
            delay(500)
        } } finally {car=null} }
    }
    val actions=object:CarV10Actions {
        override fun toggle()=player.toggle()
        override fun previous()=player.previous()
        override fun next()=player.next()
        override fun playRecommendation() {
            val snapshot=recommendation ?: return
            if(!recommendationReady || pending!=null || music?.isCurrentDrive(snapshot)==false)return
            val target=snapshot.playableRecommendations.firstOrNull() ?: return
            val track=CarMusicPlaybackGate.resolve(target,catalog) ?: run{musicStatus="현재 카탈로그에서 재생 불가 · 기존 재생 유지";return}
            music?.evidence(snapshot.run.id,target,"selected",playback.playbackSessionId.takeIf{playback.track?.id==target.mediaId})
            if(playback.track?.id==track.id && playback.playing){music?.evidence(snapshot.run.id,target,"already_playing",playback.playbackSessionId);return}
            pendingErrorBefore=playback.error;pending=snapshot to target;player.playRecommended(track,snapshot.run)
        }
    }
    BackHandler(onBack=onExit)
    CarV10Theme(dark) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            onEndCollection?.let { end -> TextButton(onClick={if(V13LiveProjection.canEdit(ux,android.os.SystemClock.elapsedRealtime()))end()},enabled=V13LiveProjection.canEdit(ux,android.os.SystemClock.elapsedRealtime()),
                modifier=Modifier.heightIn(min=64.dp).testTag("car_end_collection")){Text("수집 종료 · 음악 정리")} }
            TextButton(onClick=onExit,modifier=Modifier.heightIn(min=64.dp).testTag("car_exit")){Text("모바일 화면으로")}
            }
            if(contextDetails)CarV12ContextPanel(recommendation?.contextSnapshot ?: contextSnapshot,ux,{contextDetails=false},recommendationBound=recommendation?.contextSnapshot!=null)
            else V13LiveCarPlayer(player,playback,car,ux,"$musicStatus · $sourceDisclosure",actions,
                recommendation?.playableRecommendations?.firstOrNull(),
                pending==null && recommendationReady && recommendation?.run?.driveId==car?.driveSessionId,
                approvedArt ?: playback.artwork,{contextDetails=true},Modifier.weight(1f))
        }
    }
}
