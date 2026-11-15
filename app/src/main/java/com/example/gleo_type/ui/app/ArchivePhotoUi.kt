package com.example.gleo_type.ui.app

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable internal fun ArchivePhotoEditor(key:String,store:ArchivePhotoStore,initial:Pin?=null,
 onCancel:()->Unit,onSaved:()->Unit,onSave:(ArchiveMediaDraft,(CarV10SaveResult)->Unit)->Unit) {
 var wrapper=LocalContext.current
 while(wrapper is ContextWrapper && wrapper !is ComponentActivity)wrapper=wrapper.baseContext
 val owner=wrapper as? ComponentActivity
 if(owner==null){Text("사진 선택 화면을 연결할 수 없습니다");return}
 val vm=remember(owner,key,store){
  val factory=object:AbstractSavedStateViewModelFactory(owner,null){
   override fun <T:ViewModel> create(k:String,modelClass:Class<T>,handle:SavedStateHandle):T {
    @Suppress("UNCHECKED_CAST") return ArchivePhotoViewModel(handle,store) as T
   }
  }
  ViewModelProvider(owner,factory)["archive-photo-$key",ArchivePhotoViewModel::class.java]
 }
 LaunchedEffect(initial?.id){if(initial!=null)vm.initialize(initial)}
 val state by vm.state.collectAsState()
 var saving by remember{mutableStateOf(false)};var saveError by remember{mutableStateOf<String?>(null)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)){vm.importSelection(it)}
 BackHandler {if(!saving&&!state.importing)onCancel()}
 ReviewFinalTheme {
 V13Background {
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp).testTag("v13_photo_editor"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Text("오늘의 사진도 기억할까요?",fontSize=25.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
    Text("주행에서 들은 음악과 사진을 하나의 Hearts로",fontSize=12.sp,color=V13Design.secondary)
    val selected=state.photos.firstOrNull{it.id==state.coverId} ?: state.photos.firstOrNull()
    Box(Modifier.fillMaxWidth().heightIn(min=190.dp).clip(RoundedCornerShape(24.dp)).background(V13Design.glass),contentAlignment=Alignment.Center) {
     if(selected==null)Text("음악이 머문 순간\n선택한 사진이 없습니다",Modifier.padding(28.dp),color=V13Design.secondary)
     else ArchivePhotoImage(selected,Modifier.fillMaxWidth().height(260.dp))
    }
    Surface(shape=RoundedCornerShape(18.dp),color=V13Design.glass,border=BorderStroke(1.dp,V13Design.stroke)) {
     Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.CenterVertically) {
      V13Svg("private-gallery",Modifier.size(26.dp))
      Text("사진첩 전체 접근 없이 · 선택한 사진만 Hearts에 첨부해요",fontSize=13.sp)
     }
    }
    if(state.photos.isNotEmpty()) {
     Text("대표 이미지 선택 · ${state.photos.size}/5",fontSize=14.sp)
     Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      state.photos.forEachIndexed{i,p->OutlinedButton(onClick={vm.cover(p.id)},enabled=!saving&&!state.importing,
       modifier=Modifier.testTag("v13_photo_cover_$i")){Text(if(p.id==state.coverId)"✓ 사진 ${i+1}" else "사진 ${i+1}")}}
     }
    }
    OutlinedTextField(state.note,vm::note,label={Text("메모")},enabled=!saving&&!state.importing,
     modifier=Modifier.fillMaxWidth().testTag("v13_photo_note"),maxLines=5)
    state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("v13_photo_error"))}
    saveError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    Text("로컬 저장 · 사진 전송 및 서버 ACK 미연결",fontSize=12.sp,color=V13Design.secondary)
   }
   ReviewFinalAction(if(state.importing)"사진을 가져오는 중…" else "사진 추가하기",!saving&&!state.importing&&state.photos.size<5,
    "v13_photo_pick",Modifier.fillMaxWidth(),primary=true){picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
   ReviewFinalAction(if(saving)"저장 중…" else if(state.photos.isEmpty())"사진 없이 기록 저장" else "Hearts에 저장",!saving&&!state.importing,
    "v13_photo_save",Modifier.fillMaxWidth(),primary=state.photos.isNotEmpty()){
     saving=true;saveError=null
     onSave(vm.draft()){result->saving=false;if(result.persistedReference!=null)onSaved() else saveError=result.message}
   }
   TextButton(onClick=onCancel,enabled=!saving&&!state.importing,modifier=Modifier.fillMaxWidth().testTag("v13_photo_cancel")){Text("이전으로")}
  }
 }
}
}

@Composable internal fun ArchivePhotoImage(photo:ArchiveAttachment,modifier:Modifier=Modifier) {
 val context=LocalContext.current
 val bitmap by produceState<android.graphics.Bitmap?>(null,photo.localReference){value=withContext(Dispatchers.IO){ArchiveCover.decode(context,photo)}}
 if(bitmap!=null)Image(bitmap!!.asImageBitmap(),photo.label,modifier,contentScale=ContentScale.Crop)
 else Box(modifier.background(V13Design.glass),contentAlignment=Alignment.Center){Text("사진 미리보기 없음",fontSize=12.sp)}
}

@Composable internal fun V13HeartCard(pin:Pin,compact:Boolean=false,onClick:()->Unit) {
 val cover=ArchiveCover.selected(pin)
 Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant,
  border=BorderStroke(1.dp,V13Design.stroke),modifier=Modifier.fillMaxWidth().testTag("pin_${pin.id}").semantics{contentDescription="Hearts 상세 열기"}) {
  Box(Modifier.padding(6.dp).fillMaxWidth().then(if(cover==null)Modifier.heightIn(min=150.dp) else Modifier.aspectRatio(if(compact)161f/230f else 351f/246f)).clip(RoundedCornerShape(13.dp))) {
   if(cover!=null)ArchivePhotoImage(cover,Modifier.fillMaxSize())
   else Text("사진 없는 음악 기록",Modifier.align(Alignment.TopCenter).padding(18.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x401F1A35),Color(0xEE1F1A35)))).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
    Text(pin.track?.title ?: "저장한 순간",fontSize=17.sp,color=V13Design.foreground,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
    Text("${dateOf(pin.occurredAt ?: pin.savedAt) ?: "날짜 미확인"} · ${if(pin.place==null)"위치 미확인" else "위치 기록 있음"}",fontSize=11.sp,color=V13Design.foreground,maxLines=2)
    if(pin.note.isNotBlank())Text(pin.note,maxLines=1,fontSize=12.sp,color=V13Design.secondary,overflow=TextOverflow.Ellipsis)
   }
  }
 }
}
