package com.example.gleo_type.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.time.LocalDate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.activity.ComponentActivity
import android.content.ContextWrapper

/** Explicit repository port; no Media3/SDK/listening mutations. Local future plan, not a playback schedule. */
@Composable internal fun PlannedMusicPanel(date:LocalDate,catalog:List<Track>,repository:MusicPlanRepository) {
 var context=LocalContext.current
 while(context is ContextWrapper && context !is ComponentActivity)context=context.baseContext
 val owner=context as? ComponentActivity ?: return
 val key="music-plans-"+java.util.UUID.nameUUIDFromBytes(repository.storageKey.toByteArray(Charsets.UTF_8))
 val vm=remember(owner,key){ViewModelProvider(owner,object:ViewModelProvider.Factory{
  override fun <T:ViewModel> create(modelClass:Class<T>):T {@Suppress("UNCHECKED_CAST") return MusicPlanViewModel(repository) as T}
 })[key,MusicPlanViewModel::class.java]}
 val state by vm.state.collectAsState()
 val plans=state.plans;val error=state.error;val busy=state.busy
 var editing by rememberSaveable(date.toString()){mutableStateOf(false)}
 var title by rememberSaveable(date.toString()){mutableStateOf("다음 드라이브")}
 var selected by rememberSaveable(date.toString()){mutableStateOf<List<String>>(emptyList())}
 LaunchedEffect(date){vm.refresh()}
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  plans.filter{it.date>=LocalDate.now().toString()}.sortedBy{it.date}.forEach{plan->
   Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
     Text("${plan.date} · ${plan.title}");Text("${plan.musicIds.size}곡 · 로컬 계획 · 자동 재생/차량 전달 미연결",style=MaterialTheme.typography.bodySmall)
    }}
  }
  if(plans.isEmpty())Text("저장된 음악 일정이 없어요",style=MaterialTheme.typography.bodySmall)
  Button(onClick={val old=plans.singleOrNull{it.date==date.toString()};title=old?.title ?: "다음 드라이브";selected=old?.musicIds.orEmpty();editing=true},
   enabled=date>LocalDate.now()&&catalog.isNotEmpty(),modifier=Modifier.testTag("v13_plan_open")){Text(if(date>LocalDate.now())"${date} 음악 준비하기" else "미래 날짜를 선택해 주세요")}
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }
 if(editing)AlertDialog(onDismissRequest={if(!busy)editing=false},title={Text("여행 음악 준비하기")},text={
  Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(title,{title=it.take(120)},label={Text("일정 이름")},modifier=Modifier.testTag("v13_plan_title"))
   Text("실제 재생 카탈로그에서 선택 · 청취 기록과 별도 저장")
   TextButton(onClick={selected=catalog.map{it.id}},modifier=Modifier.testTag("v13_plan_all")){Text("현재 목록 전체 선택")}
   catalog.forEach{track->Row(Modifier.fillMaxWidth()){
    Checkbox(track.id in selected,{checked->selected=if(checked)(selected+track.id).distinct() else selected-track.id},enabled=!busy,modifier=Modifier.testTag("v13_plan_track_${track.id}"))
    Text("${track.title} · ${track.artist}")}}
  }},confirmButton={TextButton(onClick={vm.save(date,title,selected){editing=false}},enabled=!busy&&title.isNotBlank()&&selected.isNotEmpty()&&selected.all{id->catalog.any{it.id==id}},modifier=Modifier.testTag("v13_plan_save")){Text("로컬 일정 저장")}},
  dismissButton={TextButton(onClick={editing=false},enabled=!busy){Text("취소")}})
}
