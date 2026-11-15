package com.example.gleo_type.ui.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PhotoArchiveUiState(val photos:List<ArchiveAttachment> = emptyList(),val coverId:String?=null,
 val note:String="",val importing:Boolean=false,val error:String?=null)

/** UI workflow only; the existing archive repository remains the sole record owner. */
class ArchivePhotoViewModel(private val saved:SavedStateHandle,private val store:ArchivePhotoStore):ViewModel() {
 private val mutable=MutableStateFlow(restore());val state=mutable.asStateFlow()
 private fun restore():PhotoArchiveUiState=runCatching {
  val j=JSONObject(saved.get<String>("draft") ?: "{}");val a=j.optJSONArray("photos") ?: JSONArray()
  val p=(0 until a.length()).map{a.getJSONObject(it).let{x->ArchiveAttachment(x.getString("id"),"photo",x.getString("reference"),"선택한 사진","image/jpeg")}}
  val cover=j.optString("cover").takeIf{id->p.any{it.id==id}}
  PhotoArchiveUiState(p,cover,j.optString("note"))
 }.getOrDefault(PhotoArchiveUiState())
 private fun publish(s:PhotoArchiveUiState){
  mutable.value=s
  saved["draft"]=JSONObject().put("photos",JSONArray(s.photos.map{JSONObject().put("id",it.id).put("reference",it.localReference)}))
   .put("cover",s.coverId ?: JSONObject.NULL).put("note",s.note).toString()
 }
 fun initialize(pin:Pin){if(saved.get<Boolean>("initialized")==true)return
  val p=pin.attachments.filter{it.kind=="photo"}.take(5)
  publish(PhotoArchiveUiState(p,ArchiveCover.selected(pin)?.id,pin.note));saved["initialized"]=true
 }
 fun importSelection(uris:List<Uri>){
  if(mutable.value.importing)return
  if(uris.isEmpty()){mutable.value=mutable.value.copy(error=null);return}
  if(uris.size+mutable.value.photos.size>5){mutable.value=mutable.value.copy(error="사진은 최대 5개까지 선택할 수 있어요");return}
  mutable.value=mutable.value.copy(importing=true,error=null)
  viewModelScope.launch {
   val result=withContext(Dispatchers.IO){runCatching{uris.distinct().map(store::importSelected)}}
   result.onSuccess{added->val current=mutable.value;val photos=current.photos+added
    publish(current.copy(photos=photos,coverId=current.coverId ?: photos.firstOrNull()?.id,importing=false))}
    .onFailure{mutable.value=mutable.value.copy(importing=false,error="사진을 가져오지 못했어요 · 기존 선택과 기록은 유지됩니다")}
  }
 }
 fun cover(id:String){if(mutable.value.photos.any{it.id==id})publish(mutable.value.copy(coverId=id))}
 fun note(value:String)=publish(mutable.value.copy(note=value.take(2000)))
 fun draft()=mutable.value.let{ArchiveMediaDraft(it.photos,it.coverId,it.note)}
}
