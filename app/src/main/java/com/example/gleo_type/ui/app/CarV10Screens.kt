package com.example.gleo_type.ui.app

import androidx.compose.foundation.border

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gleo_type.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Caller-supplied state only. No Car, GNSS, MediaBrowser, repository or subscription creation. */
data class CarV10UiState(val playback:PlaybackUi=PlaybackUi(),val ux:DrivingUx=DrivingUx(),
    val dark:Boolean=true,val sourceDisclosure:String="실제 데이터 공급자 미연결",
    val recommendation:String="추천 정보 미제공",val vehicleStatus:String="차량 정보 unavailable",
    val vehicle:CarV10VehicleState?=null,val suggestion:CarMusicCandidate?=null,val background:CarBackground?=null,
    val recommendationEnabled:Boolean=true)
data class CarV10SaveResult(val persistedReference:String?,val message:String)
interface CarV10Actions {
    fun previous() {}
    fun toggle() {}
    fun next() {}
    fun playRecommendation() {}
    fun home() {}
    /** Null means deliberately unconnected. A nonblank persistedReference confirms actual success. */
    val remember:((String,List<String>,(CarV10SaveResult)->Unit)->Unit)? get()=null
}
private val asta=FontFamily(Font(R.font.asta_sans,FontWeight.Normal),Font(R.font.asta_sans,FontWeight.Medium),
    Font(R.font.asta_sans,FontWeight.SemiBold),Font(R.font.asta_sans,FontWeight.Bold))
private val LocalCarDark=staticCompositionLocalOf{true}

@Composable fun CarV10Theme(dark:Boolean,content:@Composable ()->Unit) {
    val t=CarV10Design.colors(dark)
    val colors=if(dark)V13Design.colors() else lightColorScheme()
    CompositionLocalProvider(LocalCarDark provides dark) { MaterialTheme(colorScheme=colors.copy(primary=if(dark)V13Design.accent else Color(t.musicPrimary),onPrimary=if(dark)Color(0xFF171128) else Color.White,
        background=if(dark)V13Design.colors().background else Color(t.backgroundPrimary),surface=if(dark)V13Design.colors().surface else Color(t.surfaceBasic),surfaceVariant=if(dark)V13Design.glass else Color(t.surfaceHigh),
        onBackground=Color(t.textPrimary),onSurface=Color(t.textPrimary),onSurfaceVariant=Color(t.textSecondary)),
        typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontFamily=asta,fontSize=20.sp),
            titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=asta,fontSize=32.sp,fontWeight=FontWeight.Bold)),content=content) }
}
@Composable private fun VText(text:String,modifier:Modifier=Modifier,size:Int=20,bold:Boolean=false,lines:Int=2) {
    Text(text,modifier,color=MaterialTheme.colorScheme.onSurface,fontFamily=asta,fontSize=size.sp,lineHeight=(size*1.3f).sp,
        fontWeight=if(bold)FontWeight.Bold else FontWeight.Normal,maxLines=lines,overflow=TextOverflow.Ellipsis)
}
@Composable private fun decodedArtwork(bytes:ByteArray?):android.graphics.Bitmap? {
    var image by remember(bytes){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(bytes){image=withContext(Dispatchers.IO){bytes?.takeIf{it.size<=2_097_152}?.let { runCatching {
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(it,0,it.size,options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192)
        options.inJustDecodeBounds=false;options.inSampleSize=1
        while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>256)options.inSampleSize*=2
        BitmapFactory.decodeByteArray(it,0,it.size,options)
    }.getOrNull() }}}
    return image
}
@Composable private fun VArtwork(playback:PlaybackUi,modifier:Modifier) {
    val image=decodedArtwork(playback.artwork)
    val art=if(LocalCarDark.current)CarV10Design.artworkDark else CarV10Design.artworkLight
    if(image!=null)Image(image.asImageBitmap(),"현재 곡 앨범아트",modifier.clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Fit)
    else Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(art[0])).padding(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().weight(.62f).clip(RoundedCornerShape(9.dp)).background(Color(art[1])))
        Box(Modifier.fillMaxWidth().weight(.38f).clip(RoundedCornerShape(9.dp)).background(Color(art[2])))
    }
}

/** App-window content only: OS status/GNB/climate/driving-view/navigation never rendered here. */
@Composable fun CarV10Player(state:CarV10UiState,actions:CarV10Actions,modifier:Modifier=Modifier) {
    CarV10Theme(state.dark) {
        val colors=state.background?.colors ?: listOf(CarV10Design.colors(state.dark).backgroundPrimary,CarV10Design.colors(state.dark).backgroundPrimary)
        val cover=decodedArtwork(state.playback.artwork)
        BoxWithConstraints(modifier.fillMaxSize().background(Brush.linearGradient(colors.map{Color(it)}))
            .testTag("car_v10_player").windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact=maxHeight<480.dp;val narrow=maxWidth<520.dp
            if(cover!=null)Image(cover.asImageBitmap(),null,Modifier.matchParentSize(),contentScale=ContentScale.Crop,alpha=.05f)
            val compactSuggestion=compact && state.suggestion!=null
            val gap=if(compact)3.dp else 12.dp
            val pad=if(compact)8.dp else if(narrow)16.dp else 24.dp
            val width=maxWidth;val height=maxHeight
            val status=when { state.playback.error!=null -> "재생 오류";!state.playback.connected -> "음악 서비스 연결 대기"
                    state.playback.buffering -> "버퍼링";state.playback.track==null -> "음악 없음"
                    state.playback.playing -> "음악 재생 중";else -> "일시정지 / 대기" }
            Column(Modifier.fillMaxSize().padding(pad),verticalArrangement=Arrangement.spacedBy(gap)) {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        VText("gleo-type",size=if(compact)20 else 30,bold=true)
                        if(compact)VText(status,Modifier.testTag("car_playback_status"),size=14,lines=1)
                    }
                    VText(state.sourceDisclosure,size=if(compact)12 else 14,lines=1)
                    if(!compact)VText(status,Modifier.testTag("car_playback_status"),size=16,lines=1)
                }
                val art=(if(compactSuggestion)64.dp else if(compact)64.dp else minOf(width*.24f,height*.25f)).coerceIn(64.dp,260.dp)
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    VArtwork(state.playback,Modifier.size(art))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        VText(state.playback.track?.title ?: "음악을 선택해 주세요",Modifier.testTag("car_track_title"),if(compact)20 else 32,true)
                        VText(state.playback.track?.artist ?: "아티스트 미제공",size=if(compact)16 else 20,lines=1)
                    }
                }
                val suggestion=state.suggestion
                if(suggestion!=null)TextButton(onClick=actions::playRecommendation,
                    enabled=state.recommendationEnabled && suggestion.playable && state.playback.connected && state.playback.canPlay,
                    modifier=Modifier.fillMaxWidth().heightIn(min=64.dp).testTag("car_recommendation")) {
                    VText("${state.recommendation.substringBefore(" · ")} · 추천: ${suggestion.title} · 재생\n${state.vehicleStatus} · ${state.recommendation}",
                        Modifier.testTag("car_vehicle_status"),size=if(compact)12 else 16,lines=2)
                } else VText("${state.recommendation} · ${state.vehicleStatus}",Modifier.testTag("car_vehicle_status"),size=if(compact)12 else 16,lines=if(compact)1 else 2)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(7.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    val p=state.playback
                    fun enabled(capability:Boolean,allowEmpty:Boolean=false)=p.connected && capability &&
                        (p.track!=null || allowEmpty && p.catalogReady && p.catalogError==null)
                    @Composable fun control(name:String,icon:Int,active:Boolean,enabled:Boolean,action:()->Unit) {
                        FilledTonalButton(onClick=action,enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=92.dp).testTag("car_$name"),
                            shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.filledTonalButtonColors(
                                containerColor=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
                            Icon(painterResource(icon),when(name){"previous"->"이전 곡";"next"->"다음 곡";else->if(p.playing)"일시정지" else "재생"},Modifier.size(28.dp),
                                tint=if(active)Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    control("previous",R.drawable.car_v10_previous,false,enabled(p.canPrevious),actions::previous)
                    control("toggle",if(p.playing)R.drawable.car_v10_pause else R.drawable.car_v10_play,true,enabled(p.canPlay,true),actions::toggle)
                    control("next",R.drawable.car_v10_next,false,enabled(p.canNext),actions::next)
                }
            }
        }
    }
}

/** Review requires caller's freshness-checked UX state. Saving is adapter-acknowledged only. */
@Composable fun CarV10Review(record:DriveRecord?,tracks:List<Track>,state:CarV10UiState,actions:CarV10Actions,modifier:Modifier=Modifier,initialExcluded:List<String> = emptyList(),candidates:Map<String,CarMusicCandidate> = emptyMap(),runtimeSavedMusicIds:Set<String> = emptySet()) {
    CarV10Theme(state.dark) {
        if(record==null || record.startedAt==null || record.endedAt==null || record.durationMs==null) {
            Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) { VText("종료가 확인된 주행 기록이 없습니다") };return@CarV10Theme
        }
        val key=record.key
        var excluded by rememberSaveable(key){mutableStateOf(initialExcluded.filter { it in record.tracks })}
        var selected by rememberSaveable(key){mutableStateOf(record.tracks.firstOrNull { it !in initialExcluded })}
        var editing by rememberSaveable(key){mutableStateOf(false)}
        var status by rememberSaveable(key){mutableStateOf("IDLE")}
        var message by rememberSaveable(key){mutableStateOf("")}
        var receipt by rememberSaveable(key){mutableStateOf<String?>(null)}
        var knownTracks by rememberSaveable(key){mutableStateOf(record.tracks)}
        var active by remember(key){mutableStateOf(true)}
        DisposableEffect(key){onDispose{active=false}}
        LaunchedEffect(key){if(status=="SAVING"){status="FAILED";message="저장 결과 미확인 · 원본 기록 유지"}}
        val validIds=record.tracks.toSet()
        val draft=remember(record,excluded,selected,editing){CarV10ReviewDraft.fromDrive(record,
            CarV10ReviewSnapshot(key,selected?.takeIf{it in validIds},excluded.filter{it in validIds},editing))}
        LaunchedEffect(record.tracks) {
            if(knownTracks!=record.tracks) {
                knownTracks=record.tracks;excluded=draft.excludedMusicIds.toList();selected=draft.selectedMusicId
                status="IDLE";receipt=null;message="관측 음악 목록이 갱신됐습니다 · 저장 전 확인"
            }
        }
        fun update(){excluded=draft.excludedMusicIds.toList();selected=draft.selectedMusicId;editing=draft.editing
            status="IDLE";message="";receipt=null}
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(state.ux){while(true){now=android.os.SystemClock.elapsedRealtime();kotlinx.coroutines.delay(1000)}}
        val safe=state.ux.complexAllowed && (state.ux.source!="CarUxRestrictionsManager" ||
            state.ux.observedElapsedMs?.let { now-it in 0..6000 }==true)
        val scope=rememberCoroutineScope()
        BoxWithConstraints(modifier.fillMaxSize().background(if(state.dark)V13Design.carBackground else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background,MaterialTheme.colorScheme.background)))
            .testTag("car_v10_review").windowInsetsPadding(WindowInsets.safeDrawing)) {
            val cardWidth=if(maxWidth<600.dp)180.dp else 194.dp
            V13Background(car=true) {}
            val short=maxHeight<650.dp
            val reviewVertical=rememberScrollState()
            var restoreViewport by remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize().padding(if(short)20.dp else 24.dp).verticalScroll(reviewVertical),verticalArrangement=Arrangement.spacedBy(if(short)8.dp else 16.dp)) {
                VText("gleo-type",size=18)
                VText("오늘 함께한 음악입니다",size=30,bold=true)
                VText("${state.sourceDisclosure} · "+if(safe)"정차 후 · 원본 청취 기록 유지" else "주행 규제 상태 미확인 또는 제한 중 · 편집 불가",Modifier.testTag("car_review_safety"),size=14,lines=1)
                if(record.tracks.isEmpty())VText("이 주행에는 관측된 음악이 없습니다",Modifier.testTag("car_review_empty"))
                val reviewScroll=rememberLazyListState()
                LaunchedEffect(restoreViewport) { if(restoreViewport>0){reviewScroll.scrollToItem(0);reviewVertical.scrollTo(0)} }
                LazyRow(Modifier.fillMaxWidth().height(if(short)284.dp else 304.dp).testTag("car_review_cards"),state=reviewScroll,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                    items(draft.includedMusicIds,key={it}) { id ->
                        val track=tracks.firstOrNull{it.id==id} ?: Track(id,id,"메타데이터 미제공")
                        val candidate=candidates[id]
                        val cardBackground=candidate?.let { CarBackgroundPolicy.resolve(CarAcousticFeatures(it.trackId,it.bpmEstimated,it.energy,it.moods),state.dark) }
                        var drag by remember(id){mutableFloatStateOf(0f)}
                        val offset by animateFloatAsState(drag,if(drag==0f)spring() else spring(stiffness=10000f),label="card-lift")
                        val threshold=with(LocalDensity.current){108.dp.toPx()}
                        val fraction=CarV10ReviewDraft.exclusionFraction(offset,threshold)
                        LaunchedEffect(safe){if(!safe)drag=0f}
                        Column(Modifier.animateItem().width(cardWidth).height(284.dp)
                            .graphicsLayer{translationY=offset}.clip(RoundedCornerShape(14.dp))
                            .background(if(fraction>0)Color(CarV10Design.exclusion).copy(alpha=.15f+.85f*fraction) else MaterialTheme.colorScheme.surfaceVariant)
                            .then(if(cardBackground!=null)Modifier.background(Brush.linearGradient(cardBackground.colors.map{Color(it)})) else Modifier)
                            .drawWithContent{drawContent();if(fraction>0)drawRect(Color(CarV10Design.exclusion).copy(alpha=fraction*.45f))}
                            .testTag("car_card_$id").border(if(selected==id)3.dp else 1.dp,if(selected==id)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(14.dp)).semantics{stateDescription=if(selected==id)"선택" else "관측 음악"}
                            .clickable(enabled=safe && status!="SAVING"){draft.select(id,true);update()}
                            .pointerInput(id,safe,status){if(safe && status!="SAVING")detectVerticalDragGestures(
                                onVerticalDrag={c,amount->c.consume();drag=(drag+amount).coerceIn(-threshold*1.5f,0f)},
                                onDragCancel={drag=0f},onDragEnd={draft.releaseUpward(id,drag,threshold,safe);update();drag=0f})}
                            .padding(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            val artContext=androidx.compose.ui.platform.LocalContext.current
                            val artLoader=remember(artContext){ReviewArtworkLoader(artContext)}
                            ReviewFinalArtwork(id,artLoader,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                            VText(track.title,size=17,bold=true,lines=1);VText(track.artist,size=13,lines=1)
                            if(id in runtimeSavedMusicIds)VText("주행 중 Hearts 저장됨",size=12)
                            val observations=record.playbacks.filter { it.musicId==id }
                            VText("${observations.size}개 재생 구간 · ${observations.sumOf { it.creditedWallMs }.toLong()/1000}초 관측",size=12,lines=1)
                        }
                    }
                }
                if(excluded.isNotEmpty())Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    VText("${excluded.size}곡 제외 예정",size=16)
                    TextButton(enabled=safe && status!="SAVING",onClick={excluded.toList().forEach{draft.restore(it,true)};update();restoreViewport++},modifier=Modifier.heightIn(min=64.dp).testTag("car_restore")){VText("제외 복원",size=18)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    FilledTonalButton(enabled=safe && status!="SAVING",onClick={draft.beginEditing(true);update();message="저장 전 편집 중"},
                        modifier=Modifier.weight(1f).heightIn(min=if(short)64.dp else 92.dp).testTag("car_edit"),shape=RoundedCornerShape(14.dp)){VText("수정하기")}
                    Button(enabled=safe && draft.includedMusicIds.isNotEmpty() && status!="SAVING" && status!="SAVED",onClick={
                        val save=actions.remember
                        if(save==null){status="NOT_CONNECTED";message="저장소 미연결 · 아직 저장하지 않았습니다"}
                        else {
                            status="SAVING";message="기록 저장 중 · 취향 학습과 별개"
                            try { save(key,draft.includedMusicIds.toList()){result->scope.launch {
                                if(active && status=="SAVING") {
                                    receipt=result.persistedReference?.takeIf{it.isNotBlank()}
                                    status=if(receipt!=null)"SAVED" else "FAILED";message=result.message
                                }
                            }} }catch(_:Exception){status="FAILED";message="저장 실패 · 초안과 원본 유지"}
                        }
                    },modifier=Modifier.weight(1f).heightIn(min=if(short)64.dp else 92.dp).testTag("car_remember"),shape=RoundedCornerShape(14.dp)){VText(if(status=="SAVING")"저장 중" else "기억하기")}
                }
                if(message.isNotEmpty())VText(message,Modifier.testTag("car_save_status").semantics{stateDescription=status},size=16)
                if(status=="SAVED" && receipt!=null)TextButton(onClick=actions::home,modifier=Modifier.heightIn(min=64.dp).testTag("car_home")){VText("차량 앱 홈으로")}
            }
        }
    }
}
