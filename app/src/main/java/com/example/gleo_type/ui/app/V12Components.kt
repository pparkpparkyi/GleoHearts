package com.example.gleo_type.ui.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.caverock.androidsvg.SVG
import com.example.gleo_type.context.*
import java.time.LocalDate

@Composable internal fun V12SystemBars(dark:Boolean) {
    val view=LocalView.current
    var context=LocalContext.current
    while(context is android.content.ContextWrapper && context !is android.app.Activity)context=context.baseContext
    val activity=context as? android.app.Activity
    DisposableEffect(activity,view,dark) {
        val controller=activity?.let{androidx.core.view.WindowCompat.getInsetsController(it.window,view)}
        val previousStatus=controller?.isAppearanceLightStatusBars
        val previousNavigation=controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars=!dark
        controller?.isAppearanceLightNavigationBars=!dark
        onDispose {
            if(previousStatus!=null)controller?.isAppearanceLightStatusBars=previousStatus
            if(previousNavigation!=null)controller?.isAppearanceLightNavigationBars=previousNavigation
        }
    }
}

/** Original downloaded Figma SVG, rendered without redrawing or extracting path data. */
@Composable internal fun V12Icon(name:String,description:String?,modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary) {
 SubmissionVector(name,modifier)
}
@Composable internal fun V12BottomBar(tab:Int,review:Boolean=false,onTab:(Int)->Unit) {
    if(review || MaterialTheme.colorScheme.background==V13Design.colors().background){V13BottomBar(tab,onTab);return}
    val colors=MaterialTheme.colorScheme
    val largeText=LocalDensity.current.fontScale>1.25f
    Surface(color=colors.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=6.dp)) {
            val icons=listOf("imgIcon","imgIcon1","imgIcon2","imgIconPin","imgIcon3")
            listOf("홈","음악","캘린더","Hearts","마이").forEachIndexed { i,label ->
                Column(Modifier.weight(1f).heightIn(min=64.dp).testTag("nav_$i").semantics{selected=tab==i}
                    .clickable(interactionSource=remember{MutableInteractionSource()},indication=null){onTab(i)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Box(Modifier.size(44.dp,34.dp).background(if(tab==i)colors.primaryContainer else Color.Transparent,RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center) {
                        V12Icon(icons[i],null,color=if(tab==i)colors.primary else colors.onSurfaceVariant)
                    }
                    Box(Modifier.padding(top=7.dp).then(if(largeText)Modifier.height(26.dp*LocalDensity.current.fontScale) else Modifier),contentAlignment=Alignment.TopCenter) {
                        Text(label,fontSize=10.sp,color=if(tab==i)colors.primary else colors.onSurfaceVariant,maxLines=2)
                    }
                }
            }
        }
    }
}

@Composable internal fun MobileV12Profile(localId:String,history:HistorySnapshot,pinCount:Int,dark:Boolean,onDark:(Boolean)->Unit,
    managing:Boolean,onManaging:(Boolean)->Unit,onPreferences:()->Unit,onCalendar:()->Unit,onDiagnostics:()->Unit) {
    var syncDetails by rememberSaveable{mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Surface(shape=RoundedCornerShape(22.dp),color=Color(0xFF002C5F),contentColor=Color.White) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.size(64.dp).background(Color(0xFF6385A8),RoundedCornerShape(40.dp)),contentAlignment=Alignment.Center){V12Icon("imgIcon3",null,Modifier.size(26.dp),Color.White)}
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("나의 Hearts",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("차량과 모바일을 이어서",fontSize=13.sp)}
                }
                Surface(color=Color(0xFF365A7E),shape=RoundedCornerShape(11.dp),contentColor=Color.White){Text("차량 데이터 · 연결 확인 필요",Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),fontSize=11.sp)}
            }
        }
        Text("개인화 설정",Modifier.padding(top=14.dp),fontSize=19.sp,fontWeight=FontWeight.Bold)
        @Composable fun settings(title:String,subtitle:String,tag:String,onClick:()->Unit) {
            OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(16.dp),colors=CardDefaults.outlinedCardColors(containerColor=colors.surface)) {
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold);Text(subtitle,fontSize=11.sp,color=colors.onSurfaceVariant)}
                    Text("›",fontSize=24.sp,color=colors.onSurfaceVariant)
                }
            }
        }
        settings("내 음악 취향","취향 선택 · 청취 피드백 확인","profile_music_preferences",onPreferences)
        settings("캘린더 연결","기기 기록 달력 · 외부 일정 미연결","v12_profile_calendar",onCalendar)
        settings("차량 데이터 · 동기화","저장 · 동기화 상태 확인","v12_sync_status"){syncDetails=!syncDetails}
        if(syncDetails)Text("서버·기기 페어링·전송 미연결. 현재 기록은 이 기기에만 보관됩니다. 전달 계약 v1은 격리 검증용이며 자동 전송하지 않습니다.",fontSize=12.sp)
        Text("개인정보 및 데이터",Modifier.padding(top=12.dp),fontSize=19.sp,fontWeight=FontWeight.Bold)
        settings("주행 · 음악 데이터 관리","위치 · 청취 기록 권한과 보관","data_management"){onManaging(!managing)}
        Row(verticalAlignment=Alignment.CenterVertically) {Text("다크모드",Modifier.weight(1f),fontSize=14.sp);Switch(checked=dark,onCheckedChange=onDark,modifier=Modifier.testTag("dark_mode"))}
        if(managing)Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("로컬 프로필 · ${localId.take(14)}…",fontSize=12.sp)
            Text("앱 수집 세션 ${history.drives.size}개 · 저장한 순간 ${pinCount}개\n계정·서버 미연결 · 기존 음악과 원본 로그를 유지합니다.",fontSize=12.sp)
            TextButton(onClick=onDiagnostics){Text("기존 SDK 진단 화면 열기")}
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable internal fun V12Heading(title:String,subtitle:String) {
    val compact=LocalConfiguration.current.screenHeightDp<500
    Column(Modifier.fillMaxWidth().padding(start=22.dp,end=22.dp,top=if(compact)8.dp else 18.dp,bottom=if(compact)12.dp else 24.dp).testTag("v12_heading"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontSize=if(compact)24.sp else 28.sp,fontWeight=FontWeight.Bold)
        if(!compact)Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun MobileV12Pins(history:HistorySnapshot,pins:List<Pin>,state:PlaybackUi,player:PlaybackPort,onPin:(Pin)->Unit,grid:@Composable ()->Unit) {
    if(!LocalHandheldUi.current){grid();return}
    var map by rememberSaveable {mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(map)"Hearts" else "Hearts 아카이브",fontSize=28.sp,fontWeight=FontWeight.Bold)
            Row {
                FilterChip(selected=map,onClick={map=true},label={Text("Map")},modifier=Modifier.testTag("v12_pin_map"))
                Spacer(Modifier.width(8.dp))
                FilterChip(selected=!map,onClick={map=false},label={Text("Grid · 검색")},modifier=Modifier.testTag("v12_pin_grid"))
            }
        }
        if(map) {var level by rememberSaveable {mutableIntStateOf(0)}
            MobileArchiveHome(history,pins,state,level,{level=it},{map=false},onPin,{map=false},player,archiveMode=true)
        } else grid()
    }
}

@Composable internal fun V12MusicCard(track:Track,state:PlaybackUi,onPlay:()->Unit,onSave:()->Unit,reason:String,showActions:Boolean=true) {
    val compact=LocalConfiguration.current.screenHeightDp<500
    val context=LocalContext.current
    val artwork=remember(context){ReviewArtworkLoader(context)}
    val observedArtwork=state.artwork?.takeIf{state.track?.id==track.id}
    if(compact) {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Surface(modifier=Modifier.testTag("v12_music_card"),shape=RoundedCornerShape(18.dp),color=V13Design.glass,contentColor=Color.White) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(64.dp).background(Color(0xFF35345D),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                        ReviewFinalArtwork(track.id,artwork,Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),observedArtwork)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(track.title,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(track.artist,fontSize=11.sp,color=Color(0xFFCBC4DB),maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(reason,fontSize=10.sp,color=Color(0xFFCBC4DB),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            if(showActions)V12MusicActions(track,state,onPlay,onSave)
        }
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Surface(modifier=Modifier.testTag("v12_music_card"),shape=RoundedCornerShape(22.dp),color=V13Design.glass,contentColor=Color.White) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(342f/269f).clip(RoundedCornerShape(17.dp)).background(Color(0xFF35345D)),contentAlignment=Alignment.Center) {
                    ReviewFinalArtwork(track.id,artwork,Modifier.fillMaxSize(),observedArtwork)
                }
                Text(track.title,fontSize=26.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(track.artist,fontSize=13.sp,color=Color(0xFFCBC4DB),maxLines=1)
                Spacer(Modifier.height(18.dp))
            }
        }
        Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){V13Svg("spark",Modifier.size(24.dp));Text("추천 이유",fontSize=14.sp,fontWeight=FontWeight.Bold)}
                Text(reason,fontSize=12.sp,modifier=Modifier.testTag("v12_actual_reason"))
            }
        }
        if(showActions)V12MusicActions(track,state,onPlay,onSave)
    }
}

@Composable internal fun V12MusicActions(track:Track,state:PlaybackUi,onPlay:()->Unit,onSave:()->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onPlay,shape=RoundedCornerShape(15.dp),modifier=Modifier.weight(1f).heightIn(min=52.dp).testTag("v12_music_start")) {Text("이 음악으로 시작")}
            FilledTonalButton(onClick=onSave,enabled=state.track?.id==track.id,shape=RoundedCornerShape(15.dp),modifier=Modifier.heightIn(min=52.dp)) {V12Icon("imgIconHeart","현재 재생 곡 Hearts 저장")}
        }
}

/** Same observed finished drive/saver. Gestures only edit the archive candidate set. */
@Composable internal fun MobileV12Review(record:DriveRecord,catalog:List<Track>,initialExcluded:List<String>,
    onClose:()->Unit,onSelectionChanged:(List<String>)->Unit={},runtimeSavedMusicIds:Set<String> = emptySet(),onSave:(List<String>,(CarV10SaveResult)->Unit)->Unit) {
    var excluded by rememberSaveable(record.key){mutableStateOf(initialExcluded.filter{it in record.tracks})}
    var saving by remember(record.key){mutableStateOf(false)}
    var savedSelection by rememberSaveable(record.key){mutableStateOf<List<String>?>(null)}
    var editing by rememberSaveable(record.key){mutableStateOf(false)}
    var message by rememberSaveable(record.key){mutableStateOf("")}
    var active by remember(record.key){mutableStateOf(true)}
    DisposableEffect(record.key){onDispose{active=false}}
    val context=LocalContext.current
    val artwork=remember(context){ReviewArtworkLoader(context)}
    val row=androidx.compose.foundation.lazy.rememberLazyListState()
    val scope=rememberCoroutineScope()
    val included=record.tracks.distinct().filterNot{it in excluded}
    val threshold=with(LocalDensity.current){90.dp.toPx()}
    BoxWithConstraints(Modifier.fillMaxSize().testTag("mobile_v12_review")) {
        val fontScale=LocalDensity.current.fontScale
        val cardWidth=((maxWidth-30.dp)*.442f).coerceIn(132.dp,250.dp)
        val imageHeight=(cardWidth-12.dp)*202f/150f
        val cardHeight=imageHeight+80.dp*maxOf(1f,fontScale)
        val stackedActions=maxWidth<360.dp || fontScale>1.4f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                Column(Modifier.padding(start=20.dp,end=20.dp,top=23.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("오늘의 음악",fontSize=27.sp,fontWeight=FontWeight.Bold)
                    Text("좌우 탐색 · 위로 올리면 저장 후보에서 제외",fontSize=12.sp,color=ReviewFinalDesign.secondary)
                }
                Box(Modifier.padding(horizontal=20.dp).fillMaxWidth().heightIn(min=44.dp)
                    .background(ReviewFinalDesign.glass,RoundedCornerShape(17.dp)).border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center) {
                    Text("이 주행에서 관측한 ${record.tracks.distinct().size}곡 · 선택 ${included.size}곡",Modifier.padding(10.dp),fontSize=13.sp,fontWeight=FontWeight.Bold)
                }
                LazyRow(Modifier.padding(horizontal=15.dp).fillMaxWidth().height(cardHeight+60.dp)
                    .background(Color.White.copy(alpha=.10f),RoundedCornerShape(21.dp)).border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(21.dp))
                    .testTag("mobile_review_cards"),state=row,contentPadding=PaddingValues(start=10.dp,end=20.dp,top=14.dp,bottom=46.dp),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                    items(included,key={it}) { id ->
                        val track=catalog.singleOrNull{it.id==id} ?: Track(id,id,"메타데이터 미제공")
                        var drag by remember(id){mutableFloatStateOf(0f)}
                        val offset by androidx.compose.animation.core.animateFloatAsState(drag,label="review-return")
                        val fraction=(-offset/threshold).coerceIn(0f,1f)
                        Column(Modifier.width(cardWidth).height(cardHeight).graphicsLayer{translationY=offset}
                            .clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha=.15f))
                            .border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(17.dp))
                            .drawWithContent{drawContent();if(fraction>0)drawRect(Color(0xFFB52939).copy(alpha=fraction*.5f))}
                            .testTag("mobile_review_$id").pointerInput(id,saving) {
                                if(!saving)detectVerticalDragGestures(onVerticalDrag={c,amount->c.consume();drag=(drag+amount).coerceIn(-threshold*1.5f,0f)},
                                    onDragCancel={drag=0f},onDragEnd={if(GesturePolicy.dismiss(drag,threshold)){excluded=excluded+id;onSelectionChanged(excluded);message=""};drag=0f})
                            }.padding(6.dp)) {
                            ReviewFinalArtwork(id,artwork,Modifier.fillMaxWidth().height(imageHeight).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.fillMaxWidth().weight(1f).background(Color.White.copy(alpha=.16f),RoundedCornerShape(10.dp))
                                .border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(10.dp)).padding(horizontal=8.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(track.title,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(id in runtimeSavedMusicIds)Text("주행 중 Hearts에 보관한 음악",fontSize=11.sp,modifier=Modifier.testTag("runtime_saved_$id"))
                                Text(track.artist,fontSize=11.sp,color=ReviewFinalDesign.secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if(included.isEmpty())Text("선택된 음악이 없습니다 · 제외한 곡을 복원해 주세요",Modifier.padding(horizontal=20.dp),fontSize=13.sp)
                Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("선택한 곡만 Hearts에 기억해요",fontSize=14.sp,color=ReviewFinalDesign.secondary)
                    Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        ReviewFinalAction("‹",row.canScrollBackward && !saving,"mobile_review_previous",Modifier.width(74.dp)){scope.launch{row.animateScrollToItem((row.firstVisibleItemIndex-1).coerceAtLeast(0))}}
                        ReviewFinalAction("›",row.canScrollForward && !saving,"mobile_review_next",Modifier.width(74.dp)){scope.launch{row.animateScrollToItem((row.firstVisibleItemIndex+1).coerceAtMost(included.lastIndex.coerceAtLeast(0)))}}
                        if(excluded.isNotEmpty())TextButton(onClick={excluded=emptyList();onSelectionChanged(excluded);message="";scope.launch{row.scrollToItem(0)}},enabled=!saving,modifier=Modifier.testTag("mobile_review_undo")){Text("${excluded.size}곡 복원",color=ReviewFinalDesign.foreground)}
                    }
                    if(editing)Text("저장 후보 재검토 중 · 제외/복원으로 선택을 조정하세요",fontSize=12.sp,color=ReviewFinalDesign.secondary)
                    if(message.isNotEmpty())Text(message,Modifier.testTag("mobile_review_result"),fontSize=13.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
            val saveEnabled=!saving && included.isNotEmpty() && savedSelection!=included
            val save:()->Unit={if(saveEnabled){val selection=included.toList();saving=true
                try {onSave(selection){result->scope.launch{if(active){saving=false;message=result.message;if(!result.persistedReference.isNullOrBlank())savedSelection=selection}}}}
                catch(_:Exception){saving=false;message="저장 실패 · 선택을 유지했습니다"}
            }}
            @Composable fun edit(modifier:Modifier) {ReviewFinalAction("수정하기",!saving,"mobile_review_edit",modifier){editing=true;message="";scope.launch{row.animateScrollToItem(0)}}}
            @Composable fun rememberAction(modifier:Modifier) {ReviewFinalAction(if(saving)"저장 중" else "기억하기",saveEnabled,"mobile_review_remember",modifier,true,save)}
            if(stackedActions)Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                edit(Modifier.fillMaxWidth());rememberAction(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                edit(Modifier.weight(.48f));rememberAction(Modifier.weight(.52f))
            }
        }
    }
}

@Composable internal fun MobileV12Calendar(history:HistorySnapshot,pins:List<Pin>,date:LocalDate,onDate:(String)->Unit,onDrive:(DriveRecord)->Unit,onPin:(Pin)->Unit,onMonth:()->Unit,plannedContent:(@Composable ()->Unit)?=null) {
    val largeFont=LocalDensity.current.fontScale>1.25f
    val dayWidth=maxOf(46.dp,36.dp*LocalDensity.current.fontScale)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().then(if(largeFont)Modifier.horizontalScroll(rememberScrollState()) else Modifier),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            (0..6).forEach { offset -> val d=date.minusDays(3).plusDays(offset.toLong())
                Surface(onClick={onDate(d.toString())},modifier=if(largeFont)Modifier.width(dayWidth) else Modifier.weight(1f),shape=RoundedCornerShape(17.dp),color=if(d==date)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,contentColor=if(d==date)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text(listOf("월","화","수","목","금","토","일")[d.dayOfWeek.value-1],fontSize=11.sp);Text("${d.dayOfMonth}",fontSize=17.sp,fontWeight=FontWeight.Bold)}
                }
            }
        }
        TextButton(onClick=onMonth,modifier=Modifier.testTag("v12_month_view")){Text("${date.year}년 ${date.monthValue}월 · 월간 달력")}
        Text("다가오는 일정",fontSize=20.sp,fontWeight=FontWeight.Bold)
        if(plannedContent!=null)plannedContent() else Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {Text("로컬 일정 공급자 미연결",Modifier.padding(20.dp),fontSize=13.sp)}
        Text("지난 음악 기록",fontSize=20.sp,fontWeight=FontWeight.Bold)
        val (drives,saved)=ArchiveProjection.onDate(history,pins,date)
        if(drives.isEmpty() && saved.isEmpty())Text("이 날짜에는 기록이 없어요",fontSize=14.sp)
        drives.forEach { record -> OutlinedCard(onClick={onDrive(record)},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("${record.date ?: "날짜 미확인"} · ${record.tracks.size}곡",fontWeight=FontWeight.Bold);Text("듣기 기록 확인  ›",fontSize=13.sp)}} }
        saved.forEach { pin -> V13HeartCard(pin){onPin(pin)} }
        Text("주행 기록과 음악은 관측된 세션으로 연결됩니다",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
}

/** App window only. OEM navigation, speedometer and listening animation are not fabricated. */
private fun contextSummary(name:String,fields:Map<String,ContextField>):String {
    val fresh=fields.filterValues{it.value!=null && it.freshness==ContextFreshness.FRESH}
    if(fresh.isEmpty())return if(fields.values.any{it.freshness==ContextFreshness.STALE})"만료된 관측 · 입력 사용 안함" else "미수신 · 추정하지 않음"
    fun map(key:String)=fresh[key]?.value as? Map<*,*>
    return when(name) {
        "시간·날씨" -> {
            val time=map("deviceTime");val hour=time?.get("hour")
            (if(hour!=null)"기기 시간 ${hour}시" else "시간 미수신")+"\n"+(if("weather" in fresh)"기상 관측 수신" else "날씨 미수신")
        }
        "운전" -> map("speedState")?.get("averageSpeedKmh")?.let{"관측 평균 속도 ${if(it is Number)"%.0f".format(it.toDouble()) else it} km/h"} ?: "차량 관측 수신"
        "위치" -> map("position")?.let { p ->
            val lat=p["latitude"]?.toString()?.toDoubleOrNull();val lon=p["longitude"]?.toString()?.toDoubleOrNull()
            if(lat!=null && lon!=null)"위치 관측 수신 · 좌표 비공개" else "위치 관측 수신"
        } ?: "이동 상태 관측 수신"
        "목적지" -> "목적지 정보 관측 수신"
        "도로" -> "교통 정보 관측 수신"
        "발화" -> if("command" in fresh)"명령 수신 · 원문 비공개" else if("userClaims" in fresh)"사용자 표현 · 실제 관측과 구분" else "명령 수신"
        else -> "관측 정보 수신"
    }
}

@Composable internal fun CarV12ContextPanel(snapshot:ContextSnapshot?,ux:DrivingUx,onClose:()->Unit,recommendationBound:Boolean=false) {
    val freshUx=ux.source=="CarUxRestrictionsManager" && ux.complexAllowed && ux.observedElapsedMs?.let{android.os.SystemClock.elapsedRealtime()-it in 0..6000}==true
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0C1024),Color(0xFF35254F)))).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("왜 이 곡을 추천했나요?",fontSize=28.sp,fontWeight=FontWeight.Bold,color=Color.White)
        if(!freshUx)Text("정차·플랫폼 UX 상태 확인 후 상세 탐색 가능",color=Color(0xFFCEC1E3),modifier=Modifier.testTag("v12_context_restricted"))
        else {
            val groups=listOf("위치" to snapshot?.currentLocation,"목적지" to snapshot?.destination,"운전" to snapshot?.driving,"도로" to snapshot?.road,"시간·날씨" to snapshot?.timeWeather,"발화" to snapshot?.voice)
            groups.chunked(2).forEach { pair -> Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {pair.forEach { (name,fields) -> Surface(Modifier.weight(1f),shape=RoundedCornerShape(13.dp),color=Color(0xFF2C2C48),contentColor=Color.White) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(name,fontSize=14.sp)
                    Text(contextSummary(name,fields.orEmpty()),fontSize=13.sp,maxLines=4,overflow=TextOverflow.Ellipsis)
                }
            }} } }
            Text(if(recommendationBound)"이 추천에 전달한 시점의 맥락입니다. 미수신 항목은 추정하지 않습니다." else "현재 맥락입니다. 이 화면의 값과 이전 추천 입력의 연결은 확인되지 않았습니다.",fontSize=13.sp,color=Color(0xFFCEC1E3))
        }
        TextButton(onClick=onClose,modifier=Modifier.heightIn(min=64.dp)){Text("음악 카드로",color=Color.White)}
    }
}

@Composable internal fun CarV12Player(state:CarV10UiState,actions:CarV10Actions,onContext:()->Unit,modifier:Modifier=Modifier) {
    val voice=CarV12VoiceStatusMapper.map(VoiceRuntimePhase.WAITING,OfficialVoiceGate(),CommandSource.TEST_TEXT)
    val target=maxOf(64f,LocalPhysicalUi.current.targetDp).dp
    val freshUx=state.ux.source=="CarUxRestrictionsManager" && state.ux.complexAllowed && state.ux.observedElapsedMs?.let{android.os.SystemClock.elapsedRealtime()-it in 0..6000}==true
    BoxWithConstraints(modifier.fillMaxSize().background(V13Design.carBackground).testTag("car_v12_player")) {
        V13Background(car=true) {}
        val compact=maxHeight<520.dp
        val showArtwork=maxWidth>=400.dp && maxHeight>=340.dp
        Column(Modifier.fillMaxSize().padding(if(compact)12.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(if(compact)8.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Surface(shape=RoundedCornerShape(19.dp),color=V13Design.glass,border=BorderStroke(1.dp,V13Design.stroke)){
                 Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.CenterVertically){V13Svg("brand-heart",Modifier.size(25.dp));Text("gleo Hearts",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)}
                }
                Text("Gleo AI · 미연결",color=Color(0xFFCEC1E3),fontSize=13.sp)
            }
            // Only nonessential content scrolls. Transport stays visible even with unknown driving UX.
            Column(Modifier.weight(1f).fillMaxWidth().then(if(freshUx)Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(if(compact)6.dp else 14.dp)) {
                Text(voice.title,fontSize=if(compact)18.sp else 24.sp,color=Color(0xFFD6B7FF),modifier=Modifier.testTag("car_v12_voice_status"))
                if(!compact)Text("공식 런타임·Schema 승인 확인 필요",fontSize=13.sp,color=Color(0xFFBDB5CF))
                val artContext=LocalContext.current
                val artLoader=remember(artContext){ReviewArtworkLoader(artContext)}
                Surface(shape=RoundedCornerShape(15.dp),color=V13Design.glass,contentColor=Color.White,border=BorderStroke(1.dp,V13Design.stroke)) {
                    Row(Modifier.fillMaxWidth().padding(if(compact)10.dp else 18.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(showArtwork)state.playback.track?.let{track->ReviewFinalArtwork(track.id,artLoader,Modifier.size(if(compact)96.dp else 160.dp).clip(RoundedCornerShape(19.dp)),state.playback.artwork)}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(state.playback.track?.title ?: "음악을 선택해 주세요",fontSize=if(compact)20.sp else 28.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(!compact)Text(state.playback.track?.artist ?: "재생 중인 음악 없음",fontSize=14.sp,maxLines=1)
                        Text(when {state.playback.error!=null->"재생 오류";state.playback.buffering->"버퍼링";state.playback.playing && state.playback.playbackSessionId!=null->"Media3 재생 확인";else->"일시정지 / 대기"},fontSize=13.sp,modifier=Modifier.testTag("car_v12_playback_status"))
                    }
                }
                }
                if(!compact && freshUx) {
                    state.suggestion?.let { suggestion ->
                        fun score(v:Double?)=v?.let{"%.3f".format(java.util.Locale.ROOT,it)} ?: "미제공"
                        Text("추천 · ${suggestion.title}",color=Color.White,fontSize=18.sp)
                        Text(state.recommendation,color=Color(0xFFCEC1E3),fontSize=13.sp)
                        Text("취향 ${score(suggestion.tasteScore)} · 맥락 ${score(suggestion.contextAdjustment)} · 발화 ${score(suggestion.requestAdjustment)}\n최종 ${score(suggestion.finalScore)}",color=Color(0xFFCEC1E3),fontSize=13.sp)
                        Button(onClick=actions::playRecommendation,enabled=state.recommendationEnabled && suggestion.playable && state.playback.connected,modifier=Modifier.heightIn(min=target)){Text("추천 음악 재생")}
                    }
                    TextButton(onClick=onContext,modifier=Modifier.heightIn(min=target).testTag("car_v12_context")){Text("6대 맥락 · 추천 근거",color=Color.White)}
                    Text(state.vehicleStatus,color=Color(0xFFBDB5CF),fontSize=12.sp)
                } else if(!compact)TextButton(onClick=onContext,enabled=false,modifier=Modifier.testTag("car_v12_context")){Text("맥락 상세 · 정차 상태 확인 필요")}
                if(compact && freshUx)TextButton(onClick=onContext,modifier=Modifier.testTag("car_v12_context")){Text("6대 맥락",color=Color.White)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val initialRecommendation=state.playback.track==null && state.suggestion?.playable==true && state.recommendationEnabled
                listOf("이전" to actions::previous,(if(initialRecommendation)"추천 재생" else if(state.playback.playing)"일시정지" else "재생") to (if(initialRecommendation)actions::playRecommendation else actions::toggle),"다음" to actions::next).forEachIndexed { i,(label,action) ->
                    val allowed=state.playback.connected && when(i){0->state.playback.canPrevious;1->state.playback.canPlay;else->state.playback.canNext}
                    val icon=when(i){0->com.example.gleo_type.R.drawable.car_v10_previous;2->com.example.gleo_type.R.drawable.car_v10_next;else->if(state.playback.playing)com.example.gleo_type.R.drawable.car_v10_pause else com.example.gleo_type.R.drawable.car_v10_play}
                    FilledTonalButton(onClick=action,enabled=allowed,modifier=Modifier.weight(1f).heightIn(min=target).testTag(listOf("car_v12_previous","car_v12_toggle","car_v12_next")[i]),shape=RoundedCornerShape(13.dp)) {Icon(painterResource(icon),label,Modifier.size(30.dp))}
                }
            }
        }
    }
}
