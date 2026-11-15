package com.example.gleo_type.ui.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gleo_type.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit opt-in only for the preserved SDK renderer; mobile defaults to an offline image. */
enum class ArchiveMapRenderer { STATIC_IMAGE, GOOGLE_MAPS }

/** Cards are chronological links, never fabricated pins at geographic/image coordinates. */
@Composable internal fun StaticHeartsMap(pins:List<Pin>,padding:PaddingValues,onPin:(Pin)->Unit) {
 val records=remember(pins){pins.filter{it.deletedAt==null}.sortedWith(compareByDescending<Pin>{it.occurredAt ?: it.savedAt}.thenBy{it.id})}
 val colors=MaterialTheme.colorScheme
 Box(Modifier.fillMaxSize().testTag("hearts_static_map")) {
  Image(painterResource(R.drawable.hearts_map_background),"교체 가능한 예시 지도 배경 · 실제 위치 매핑 없음",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
  Box(Modifier.fillMaxSize().background(colors.background.copy(alpha=if(colors.background==V13Design.colors().background).84f else .32f)))
  Column(Modifier.fillMaxSize().padding(start=16.dp,end=16.dp,top=16.dp,bottom=padding.calculateBottomPadding())
   .verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Surface(shape=RoundedCornerShape(16.dp),color=colors.surface.copy(alpha=.95f)) {
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text("이미지 지도 · 위치 배치 미연결",fontSize=14.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("static_map_disclosure"))
     Text("예시 배경입니다. 아래 카드는 날짜순이며 실제 지리적 위치를 뜻하지 않아요.",fontSize=12.sp)
    }
   }
   if(records.isEmpty())Surface(shape=RoundedCornerShape(16.dp),color=colors.surface.copy(alpha=.95f)) {
    Text("아직 저장한 Hearts가 없어요",Modifier.padding(16.dp).testTag("static_map_empty"),fontSize=14.sp)
   } else LazyRow(Modifier.fillMaxWidth().testTag("static_place_cards"),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    items(records,key={it.id}) { pin ->
     Surface(onClick={onPin(pin)},shape=RoundedCornerShape(18.dp),color=colors.surface.copy(alpha=.97f),
      modifier=Modifier.width(248.dp).heightIn(min=112.dp).testTag("static_heart_${pin.id}")) {
      Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       ArchiveCover.selected(pin)?.let{ArchivePhotoImage(it,Modifier.fillMaxWidth().height(90.dp))}
       Text(pin.track?.title ?: "저장한 순간",fontSize=16.sp,fontWeight=FontWeight.Bold)
       pin.track?.artist?.let{Text(it,fontSize=12.sp)}
       Text("${dateOf(pin.occurredAt ?: pin.savedAt) ?: "날짜 미확인"} · ${if(validPlace(pin.place?.latitude,pin.place?.longitude)!=null)"장소 기록" else "위치 미확인"}",fontSize=12.sp)
       Text("Hearts 상세 보기 ›",fontSize=12.sp,color=colors.primary)
      }
     }
    }
   }
  }
 }
}

/** Read-only, bounded local photo previews. No network URI, uploads or archive/schema writes. */
@Composable internal fun HeartLocalPhotos(pin:Pin) {
 val context=LocalContext.current
 val photos=pin.attachments.filter{it.kind=="photo"}.sortedBy{if(it.id==pin.coverAttachmentId)0 else 1}
 if(photos.isEmpty())return
 Text("사진 ${photos.size}개 · 로컬 미리보기",fontSize=14.sp)
 photos.take(3).forEach { attachment -> key(attachment.id,attachment.localReference) {
  val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,attachment.localReference) {
   value=withContext(Dispatchers.IO){ArchiveCover.decode(context,attachment)?.asImageBitmap()}
  }
  if(bitmap!=null)Image(bitmap!!,attachment.label,Modifier.fillMaxWidth().height(160.dp).testTag("heart_local_photo_${attachment.id}"),contentScale=ContentScale.Fit)
  else Text("사진을 표시할 수 없습니다 · 원본 기록은 유지됩니다",fontSize=12.sp)
 }}
 if(photos.size>3)Text("처음 3개만 미리보기 · 업로드/외부 사진 열기 미지원",fontSize=12.sp)
}
