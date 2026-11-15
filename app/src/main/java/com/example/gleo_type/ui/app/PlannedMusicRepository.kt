package com.example.gleo_type.ui.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*
import java.util.UUID

data class MusicPlan(val id:String,val date:String,val zoneId:String,val title:String,val musicIds:List<String>,val updatedAt:String) {
 init {require(id.isNotBlank()&&title.isNotBlank()&&title.length<=120);LocalDate.parse(date);ZoneId.of(zoneId);Instant.parse(updatedAt)
  require(musicIds.isNotEmpty()&&musicIds.size<=100&&musicIds.distinct()==musicIds&&musicIds.all{it.isNotBlank()})}
}
interface MusicPlanRepository {val storageKey:String get()=System.identityHashCode(this).toString();fun load():List<MusicPlan>;fun save(date:LocalDate,title:String,musicIds:List<String>):MusicPlan}

/** Plans are never projected into JSONL/history/listening. No scheduler or CAR delivery is implied. */
class LocalMusicPlanRepository(private val file:File,private val localUserId:String,
 private val zone:ZoneId=ZoneId.systemDefault(),private val now:()->Instant=Instant::now):MusicPlanRepository {
 companion object{private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()}
 private val lock=locks.getOrPut(file.canonicalPath){Any()}
 override val storageKey get()=file.canonicalPath+"/"+localUserId
 override fun load():List<MusicPlan> = synchronized(lock){
  if(!file.exists()&&!File(file.path+".bak").exists())return@synchronized emptyList()
  val json=JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  require(json.getInt("version")==1&&json.getString("localUserId")==localUserId)
  val a=json.getJSONArray("plans");(0 until a.length()).map{a.getJSONObject(it).let{p->
   MusicPlan(p.getString("id"),p.getString("date"),p.getString("zoneId"),p.getString("title"),p.getJSONArray("musicIds").let{ids->(0 until ids.length()).map{ids.getString(it)}},p.getString("updatedAt"))}}
 }
 override fun save(date:LocalDate,title:String,musicIds:List<String>):MusicPlan=synchronized(lock){
  require(date>now().atZone(zone).toLocalDate()){"Future date required"}
  val id=UUID.nameUUIDFromBytes((localUserId+"/planned-music/"+date).toByteArray(Charsets.UTF_8)).toString()
  val plan=MusicPlan(id,date.toString(),zone.id,title.trim(),musicIds,now().toString())
  val next=load().filterNot{it.id==id}+plan;require(next.size<=1000)
  check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs())
  val data=JSONObject().put("version",1).put("localUserId",localUserId).put("plans",JSONArray(next.map{p->JSONObject().put("id",p.id).put("date",p.date).put("zoneId",p.zoneId)
   .put("title",p.title).put("musicIds",JSONArray(p.musicIds)).put("updatedAt",p.updatedAt)})).toString().toByteArray(Charsets.UTF_8)
  val atomic=AtomicFile(file);val out=atomic.startWrite();try{out.write(data);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
  plan
 }
}
