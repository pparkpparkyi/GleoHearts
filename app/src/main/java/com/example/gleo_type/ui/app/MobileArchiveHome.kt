package com.example.gleo_type.ui.app
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gleo_type.BuildConfig
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile-only screen. No SDK location listener, storage writes or vehicle changes. */
@Composable fun MobileArchiveHome(
    history: HistorySnapshot, pins: List<Pin>, playback: PlaybackUi,
    level: Int, onLevel: (Int) -> Unit, onMusic: () -> Unit, onPin: (Pin) -> Unit,
    onArchive: () -> Unit, player: PlaybackPort,
    mapContent: (@Composable (List<HeartMapGroup>, (String) -> Unit, PaddingValues) -> Unit)? = null,
    onMapLoaded: () -> Unit = {},
    onReview: (DriveRecord) -> Unit = {}, archiveMode:Boolean=false,
    mapRenderer:ArchiveMapRenderer=ArchiveMapRenderer.STATIC_IMAGE
) {
    val groups = remember(pins) { HeartMapProjection.groups(pins) }
    val unlocated = remember(pins) { HeartMapProjection.unlocated(pins) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGroup = groups.firstOrNull { it.id == selected }
    LaunchedEffect(groups) { if(selectedGroup == null) selected = null }
    val colors=MaterialTheme.colorScheme
    val pending=history.drives.sortedByDescending{it.endedAt}.firstOrNull { it.endedAt!=null && it.durationMs!=null && it.tracks.isNotEmpty() && pins.none { pin->ArchiveProjection.linked(pin,it) } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("mobile_archive_home")) {
        val index=level.coerceIn(0,2)
        val sheetHeight by animateDpAsState(maxHeight * listOf(.24f,.48f,.90f)[index],label="mobileSheet")
        // Expanded sheet must not leave Maps with nonpositive padded camera dimensions.
        val mapTop=if(index==2 || maxWidth>maxHeight)0.dp else minOf(100.dp,maxHeight*.15f)
        val mapBottom=minOf(sheetHeight+8.dp,(maxHeight-mapTop-48.dp).coerceAtLeast(0.dp))
        val padding=PaddingValues(top=mapTop,bottom=mapBottom,start=8.dp,end=8.dp)
        val select: (String)->Unit = { selected=it; onLevel(1) }
        if(mapContent!=null) mapContent(groups,select,padding)
        else if(mapRenderer==ArchiveMapRenderer.GOOGLE_MAPS) ArchiveGoogleMap(groups,playback,select,padding,onMapLoaded)
        else Box(Modifier.fillMaxSize().padding(top=if(archiveMode)0.dp else 80.dp,start=13.dp,end=13.dp).clip(RoundedCornerShape(18.dp))){StaticHeartsMap(pins,padding,onPin)}
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(!archiveMode)Text("GleoHearts",color=colors.onBackground,fontSize=29.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(onClick=onArchive,shape=RoundedCornerShape(24.dp),color=colors.surface) {
                    Box(Modifier.size(48.dp).semantics{contentDescription="Hearts 열기"},contentAlignment=Alignment.Center){V13Svg("open-hearts",Modifier.size(24.dp))}
                }
            }
        }
        var drag by remember { mutableFloatStateOf(0f) }
        val threshold=with(LocalDensity.current){40.dp.toPx()}
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight).testTag("home_sheet")
            .semantics { stateDescription=listOf("축소","중간","확장")[index] },
            shape=RoundedCornerShape(topStart=29.dp,topEnd=29.dp),color=colors.surface.copy(alpha=.82f),border=BorderStroke(1.dp,V13Design.stroke),shadowElevation=8.dp) {
            Column {
                Column(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("sheet_handle")
                    .pointerInput(index,threshold) {detectVerticalDragGestures(
                        onVerticalDrag={change,amount->change.consume();drag+=amount},
                        onDragCancel={drag=0f},onDragEnd={onLevel(GesturePolicy.sheetLevel(index,drag,threshold));drag=0f})}
                    .clickable {onLevel((index+1)%3)},horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top=10.dp).size(44.dp,5.dp).background(colors.outlineVariant,RoundedCornerShape(3.dp)))
                    Text(if(selectedGroup!=null) "이 장소의 음악 · ${selectedGroup.pins.size}개" else if(archiveMode)"음악이 머문 장소" else "오늘의 드라이브",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),fontSize=23.sp,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(archiveMode)"장소별 Hearts · 위치 미확인 기록은 Grid에서" else "차에서 들은 음악을 기억할까요?",fontSize=12.sp,color=colors.onSurfaceVariant)
                    if(selectedGroup==null && !archiveMode) Surface(shape=RoundedCornerShape(18.dp),color=colors.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(pending?.let{"${if(it.date==java.time.LocalDate.now())"오늘" else it.date ?: "지난 주행"} 함께한 ${it.tracks.distinct().size}곡"} ?: "저장 대기 중인 주행이 없어요",fontSize=17.sp,fontWeight=FontWeight.Bold)
                            Text("이 기기의 종료된 주행 · 차량 자동 동기화 미연결",fontSize=11.sp,color=colors.onSurfaceVariant)
                            Button(onClick={pending?.let(onReview)},enabled=pending!=null,shape=RoundedCornerShape(12.dp),modifier=Modifier.heightIn(min=48.dp).testTag("v12_open_review")){Text("정리하기")}
                        }
                    }
                    if(!archiveMode)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("최근의 Hearts",fontSize=17.sp,fontWeight=FontWeight.Bold)
                        TextButton(onClick=onArchive){Text("모두 보기",fontSize=12.sp)}
                    }
                    Text("${pins.count{it.deletedAt==null}}개 Hearts · 위치 있는 기록 ${groups.sumOf{it.pins.size}}개 · 위치 미확인 ${unlocated.size}개",Modifier.testTag("home_hearts_counts"),fontSize=12.sp,color=colors.onSurfaceVariant)
                    if(index==2)Surface(onClick=onMusic,color=Color(0xFF002C5F),shape=RoundedCornerShape(19.dp)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("MUSIC",color=Color(0xFFEAF6FD),fontSize=11.sp)
                                Text(playback.track?.title ?: "음악을 선택해 주세요",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=2)
                                Text(playback.track?.artist ?: "음악 탭에서 둘러보기",color=Color(0xFFB7D0E4),fontSize=12.sp,maxLines=1)
                            }
                            FilledIconButton(onClick={if(playback.track==null)onMusic() else player.toggle()},colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFFD8FA79),contentColor=Color(0xFF002C5F))) {
                                Text(if(playback.playing) "Ⅱ" else "▶",fontSize=20.sp)
                            }
                        }
                    }
                    if(index>0 || selectedGroup!=null) {
                        val visible=selectedGroup?.pins ?: pins.filter{it.deletedAt==null}.sortedByDescending{it.occurredAt ?: it.savedAt}
                        if(visible.isEmpty()) Text("아직 저장한 순간이 없어요 · 기록이 없어도 지도는 표시됩니다",fontSize=14.sp)
                        visible.forEach { pin ->
                            Box(Modifier.testTag("home_heart_${pin.id}")){V13HeartCard(pin){onPin(pin)}}
                        }
                        if(selectedGroup!=null) TextButton(onClick={selected=null}) {Text("전체 Hearts 보기")}
                        Text("최근 앱 수집 기록 ${history.drives.size}개 · 이 기기의 로컬 기록",fontSize=12.sp,color=colors.onSurfaceVariant)
                        TextButton(onClick=onArchive) {Text("Hearts 아카이브 열기 →")}
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable private fun ArchiveGoogleMap(groups: List<HeartMapGroup>, playback: PlaybackUi,
    onSelect:(String)->Unit,padding:PaddingValues,onLoaded:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val dark=colors.background.luminance()<.5f
    val camera=rememberCameraPositionState {
        // A neutral browsing viewport, never a fabricated Heart or claimed current location.
        position=CameraPosition.fromLatLngZoom(LatLng(37.5665,126.9780),11f)
    }
    var ready by remember {mutableStateOf(false)}
    var timedOut by remember {mutableStateOf(false)}
    LaunchedEffect(ready) {if(!ready){kotlinx.coroutines.delay(15000);timedOut=true}else timedOut=false}
    val scope=rememberCoroutineScope()
    if(!BuildConfig.MAPS_CONFIGURED) {
        Box(Modifier.fillMaxSize().background(colors.surfaceVariant).testTag("map_missing_key"),contentAlignment=Alignment.Center) {
            Text("지도 키 설정이 필요합니다",color=colors.onSurfaceVariant)
        }
        return
    }
    LaunchedEffect(ready,groups.map{it.place}) {
        if(ready && groups.isNotEmpty()) {
            if(groups.size==1)camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(groups[0].place.latitude,groups[0].place.longitude),14f))
            else {
                val bounds=LatLngBounds.builder().apply{groups.forEach{include(LatLng(it.place.latitude,it.place.longitude))}}.build()
                camera.animate(CameraUpdateFactory.newLatLngBounds(bounds,60))
            }
        }
    }
    GoogleMap(Modifier.fillMaxSize().testTag("archive_google_map"),cameraPositionState=camera,
        properties=MapProperties(isMyLocationEnabled=false,mapStyleOptions=MapStyleOptions(if(dark) darkMapStyle else lightMapStyle)),
        uiSettings=MapUiSettings(mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false,compassEnabled=true),
        contentPadding=padding,onMapLoaded={ready=true;onLoaded()}) {
        groups.forEach { group -> key(group.id) {
            val context=LocalContext.current
            var photo by remember(group.pins,playback.artwork) {mutableStateOf<Bitmap?>(null)}
            LaunchedEffect(group.pins,playback.artwork) {
                photo=withContext(Dispatchers.IO) {
                    val bytes=if(group.pins.any{it.track?.id==playback.track?.id})playback.artwork else null
                    val attachment=group.pins.flatMap{it.attachments}.firstOrNull{it.kind=="photo"}
                    val data=bytes ?: attachment?.let {runCatching {context.contentResolver.openInputStream(Uri.parse(it.localReference))?.use{input->
                        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(output.size()<=2_097_152){val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)}
                        output.toByteArray().takeIf{it.size<=2_097_152}}}.getOrNull()}
                    data?.let { b -> runCatching {BitmapFactory.decodeByteArray(b,0,b.size,BitmapFactory.Options().apply{inSampleSize=4})}.getOrNull()}
                }
            }
            val icon=remember(photo,group.pins.size,dark) {BitmapDescriptorFactory.fromBitmap(heartMarkerBitmap(group.pins.size,photo,dark))}
            val position=LatLng(group.place.latitude,group.place.longitude)
            val marker=rememberUpdatedMarkerState(position=position)
            Marker(state=marker,icon=icon,title=group.pins.first().track?.title ?: "Hearts",snippet="${group.pins.size}개의 음악 기록",
                onClick={onSelect(group.id);scope.launch{camera.animate(CameraUpdateFactory.newLatLng(position))};true})
        } }
    }
    if(!ready) BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).padding(top=if(maxWidth>maxHeight)8.dp else 108.dp)) {
            Surface(shape=RoundedCornerShape(12.dp),color=colors.surface) {Text(if(timedOut) "지도를 불러오지 못했습니다 · 연결·API 설정을 확인해 주세요" else "지도 연결 중…",Modifier.padding(8.dp),fontSize=12.sp)}
        }
    }
}

/** Photo frame and exact-location count badge. A music glyph is explicit missing-artwork fallback. */
internal fun heartMarkerBitmap(count:Int,photo:Bitmap?,dark:Boolean):Bitmap {
    val bitmap=Bitmap.createBitmap(168,176,Bitmap.Config.ARGB_8888)
    val c=Canvas(bitmap);val p=Paint(Paint.ANTI_ALIAS_FLAG)
    p.color=android.graphics.Color.argb(65,0,0,0);c.drawRoundRect(RectF(13f,27f,144f,157f),18f,18f,p)
    p.color=android.graphics.Color.WHITE;c.drawRoundRect(RectF(7f,20f,139f,152f),18f,18f,p)
    val path=android.graphics.Path().apply{moveTo(57f,148f);lineTo(73f,168f);lineTo(89f,148f);close()};c.drawPath(path,p)
    val art=RectF(15f,28f,131f,144f)
    c.save();c.clipPath(android.graphics.Path().apply{addRoundRect(art,12f,12f,android.graphics.Path.Direction.CW)})
    if(photo!=null) {
        val side=minOf(photo.width,photo.height);val x=(photo.width-side)/2;val y=(photo.height-side)/2
        c.drawBitmap(photo,android.graphics.Rect(x,y,x+side,y+side),art,p)
    } else {
        p.color=if(dark)android.graphics.Color.rgb(28,39,63) else android.graphics.Color.rgb(0,44,95);c.drawRect(art,p)
        p.color=android.graphics.Color.WHITE;p.textSize=58f;p.textAlign=Paint.Align.CENTER;c.drawText("♫",73f,108f,p)
    }
    c.restore();p.color=android.graphics.Color.rgb(71,123,244);c.drawRoundRect(RectF(96f,0f,168f,39f),19f,19f,p)
    p.color=android.graphics.Color.WHITE;p.textSize=24f;p.textAlign=Paint.Align.CENTER;p.typeface=android.graphics.Typeface.DEFAULT_BOLD;c.drawText(if(count>999)"999+" else count.toString(),132f,28f,p)
    return bitmap
}

private const val lightMapStyle="""[{"featureType":"all","elementType":"geometry","stylers":[{"color":"#edf4f2"}]},{"featureType":"water","elementType":"geometry","stylers":[{"color":"#afd6e5"}]},{"featureType":"landscape.natural","elementType":"geometry","stylers":[{"color":"#d9e8d4"}]},{"featureType":"road","elementType":"geometry","stylers":[{"color":"#ffffff"}]},{"featureType":"poi","elementType":"labels","stylers":[{"visibility":"off"}]},{"featureType":"transit","stylers":[{"visibility":"off"}]},{"featureType":"all","elementType":"labels.text.fill","stylers":[{"color":"#74888e"}]}]"""
private const val darkMapStyle="""[{"elementType":"geometry","stylers":[{"color":"#202632"}]},{"elementType":"labels.text.fill","stylers":[{"color":"#aebdca"}]},{"elementType":"labels.text.stroke","stylers":[{"color":"#202632"}]},{"featureType":"water","elementType":"geometry","stylers":[{"color":"#17384a"}]},{"featureType":"road","elementType":"geometry","stylers":[{"color":"#384353"}]},{"featureType":"poi","elementType":"labels","stylers":[{"visibility":"off"}]}]"""
