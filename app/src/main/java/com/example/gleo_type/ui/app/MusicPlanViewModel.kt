package com.example.gleo_type.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class MusicPlanUiState(val plans:List<MusicPlan> = emptyList(),val busy:Boolean=false,val error:String?=null)
class MusicPlanViewModel(private val repository:MusicPlanRepository):ViewModel() {
 private val mutable=MutableStateFlow(MusicPlanUiState());val state=mutable.asStateFlow()
 private var generation=0
 init {refresh()}
 fun refresh(){if(mutable.value.busy)return;val token=++generation
  viewModelScope.launch{runCatching{withContext(Dispatchers.IO){repository.load()}}
  .onSuccess{if(token==generation)mutable.value=mutable.value.copy(plans=it,error=null)}
  .onFailure{if(token==generation)mutable.value=mutable.value.copy(error="일정을 읽지 못했어요 · 기존 파일 유지")}}}
 fun save(date:LocalDate,title:String,ids:List<String>,done:()->Unit){
  if(mutable.value.busy)return
  generation++
  mutable.value=mutable.value.copy(busy=true,error=null)
  viewModelScope.launch {
   runCatching{withContext(Dispatchers.IO){repository.save(date,title,ids)}}
    .onSuccess{saved->mutable.value=mutable.value.copy(plans=mutable.value.plans.filterNot{it.id==saved.id}+saved,busy=false);done()}
    .onFailure{mutable.value=mutable.value.copy(busy=false,error="일정 저장 실패 · 기존 기록 유지")}
  }
 }
}
