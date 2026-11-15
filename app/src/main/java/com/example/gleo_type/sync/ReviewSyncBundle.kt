package com.example.gleo_type.sync

import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

data class ReviewOccurrence(val playbackSessionId:String,val musicId:String?,val creditedWallMs:Double,
 val uniqueListenedMediaMs:Double?,val uniqueListeningRate:Double?,val confidence:String?,
 val qualityFlags:Set<String>,val recommendationId:String?=null)
data class ReviewHeartData(val archiveId:String,val musicId:String,val savedAt:String,val tags:List<String>,val note:String,val deletedAt:String?=null)
data class ReviewSyncBundle(val localUserId:String,val sourceDeviceId:String,val appRunId:String,val driveSessionId:String,
 val sourceDriveKey:String,val revision:Long,val updatedAt:String,val startedAt:String,val endedAt:String,val zoneId:String,
 val tracks:List<Track>,val occurrences:List<ReviewOccurrence>,val excludedMusicIds:Set<String> = emptySet(),
 val hearts:List<ReviewHeartData> = emptyList(),val testData:Boolean=false,val qualityFlags:Set<String> = emptySet()) {
 val bundleId get()=SyncJson.id(localUserId,sourceDeviceId,appRunId,driveSessionId)
 val reviewKey get()="sync/$bundleId"
 init {
  require(listOf(localUserId,sourceDeviceId,appRunId,driveSessionId,sourceDriveKey).all{it.isNotBlank()&&it.length<=1024})
  require(revision>0);Instant.parse(updatedAt);java.time.ZoneId.of(zoneId)
  require(!Instant.parse(endedAt).isBefore(Instant.parse(startedAt)))
  require(tracks.size<=500 && tracks.map{it.id}.distinct().size==tracks.size)
  require(tracks.all{it.id.isNotBlank()&&it.id.length<=256&&it.title.length<=4096&&it.artist.length<=4096})
  val ids=tracks.map{it.id}.toSet();require(excludedMusicIds.all{it in ids})
  require(occurrences.size<=2000 && occurrences.map{it.playbackSessionId}.distinct().size==occurrences.size)
  require(occurrences.all{it.playbackSessionId.isNotBlank()&&it.playbackSessionId.length<=1024 &&
   (it.musicId==null||it.musicId in ids)&&it.creditedWallMs.isFinite()&&it.creditedWallMs>=0 &&
   (it.uniqueListenedMediaMs==null||it.uniqueListenedMediaMs.isFinite()&&it.uniqueListenedMediaMs>=0)&&
   (it.uniqueListeningRate==null||it.uniqueListeningRate.isFinite()&&it.uniqueListeningRate in 0.0..1.0)&&
   (it.recommendationId==null||it.recommendationId.isNotBlank()&&it.recommendationId.length<=1024)})
  require(hearts.size<=500&&hearts.map{it.archiveId}.distinct().size==hearts.size)
  hearts.forEach{require(it.archiveId.isNotBlank()&&it.musicId in ids&&it.note.length<=10000&&it.tags.size<=50);Instant.parse(it.savedAt);it.deletedAt?.let(Instant::parse)}
 }
 fun driveRecord()=DriveRecord(reviewKey,driveSessionId,startedAt,endedAt,tracks.map{it.id},occurrences.sumOf{it.creditedWallMs},
  occurrences.isNotEmpty(),null,testData,playbacks=occurrences.map{PlaybackArchive(it.playbackSessionId,it.musicId,it.creditedWallMs,it.uniqueListenedMediaMs,it.uniqueListeningRate,it.confidence,it.qualityFlags)},
  qualityFlags=qualityFlags,dataSources=setOf("IMPORTED_REVIEW_BUNDLE_NOT_LOCAL_OBSERVATION"))
 fun json()=JSONObject().put("schemaVersion",1).put("bundleId",bundleId).put("localUserId",localUserId).put("sourceDeviceId",sourceDeviceId)
  .put("appRunId",appRunId).put("driveSessionId",driveSessionId).put("sourceDriveKey",sourceDriveKey).put("revision",revision).put("updatedAt",updatedAt)
  .put("startedAt",startedAt).put("endedAt",endedAt).put("zoneId",zoneId).put("testData",testData)
  .put("qualityFlags",JSONArray(qualityFlags.sorted())).put("excludedMusicIds",JSONArray(excludedMusicIds.sorted()))
  .put("tracks",JSONArray(tracks.map{JSONObject().put("musicId",it.id).put("title",it.title).put("artist",it.artist)}))
  .put("occurrences",JSONArray(occurrences.map{JSONObject().put("playbackSessionId",it.playbackSessionId).put("musicId",it.musicId ?: JSONObject.NULL)
   .put("creditedWallMs",it.creditedWallMs).put("uniqueListenedMediaMs",it.uniqueListenedMediaMs ?: JSONObject.NULL).put("uniqueListeningRate",it.uniqueListeningRate ?: JSONObject.NULL)
   .put("confidence",it.confidence ?: JSONObject.NULL).put("qualityFlags",JSONArray(it.qualityFlags.sorted())).put("recommendationId",it.recommendationId ?: JSONObject.NULL)}))
  .put("hearts",JSONArray(hearts.map{JSONObject().put("archiveId",it.archiveId).put("musicId",it.musicId).put("savedAt",it.savedAt).put("tags",JSONArray(it.tags)).put("note",it.note).put("deletedAt",it.deletedAt ?: JSONObject.NULL)}))
 fun encode()=SyncJson.canonical(json()).also{require(it.toByteArray(Charsets.UTF_8).size<=512*1024)}
 val contentHash get()=SyncJson.hash(encode())
 companion object {
  fun decode(text:String):ReviewSyncBundle {
   require(text.toByteArray(Charsets.UTF_8).size<=512*1024);val j=JSONObject(text);require(j.getInt("schemaVersion")==1)
   require(j.keys().asSequence().toSet()==setOf("schemaVersion","bundleId","localUserId","sourceDeviceId","appRunId","driveSessionId","sourceDriveKey","revision","updatedAt","startedAt","endedAt","zoneId","testData","qualityFlags","excludedMusicIds","tracks","occurrences","hearts")){"Unknown bundle fields"}
   fun strings(a:JSONArray)=(0 until a.length()).map{a.getString(it)}
   val t=j.getJSONArray("tracks");val o=j.getJSONArray("occurrences");val h=j.getJSONArray("hearts")
   val b=ReviewSyncBundle(j.getString("localUserId"),j.getString("sourceDeviceId"),j.getString("appRunId"),j.getString("driveSessionId"),j.getString("sourceDriveKey"),j.getLong("revision"),j.getString("updatedAt"),j.getString("startedAt"),j.getString("endedAt"),j.getString("zoneId"),
    (0 until t.length()).map{t.getJSONObject(it).let{x->Track(x.getString("musicId"),x.getString("title"),x.getString("artist"))}},
    (0 until o.length()).map{o.getJSONObject(it).let{x->ReviewOccurrence(x.getString("playbackSessionId"),SyncJson.optional(x,"musicId"),x.getDouble("creditedWallMs"),if(x.isNull("uniqueListenedMediaMs"))null else x.getDouble("uniqueListenedMediaMs"),if(x.isNull("uniqueListeningRate"))null else x.getDouble("uniqueListeningRate"),SyncJson.optional(x,"confidence"),strings(x.getJSONArray("qualityFlags")).toSet(),SyncJson.optional(x,"recommendationId"))}},
    strings(j.getJSONArray("excludedMusicIds")).toSet(),(0 until h.length()).map{h.getJSONObject(it).let{x->ReviewHeartData(x.getString("archiveId"),x.getString("musicId"),x.getString("savedAt"),strings(x.getJSONArray("tags")),x.getString("note"),SyncJson.optional(x,"deletedAt"))}},j.getBoolean("testData"),strings(j.getJSONArray("qualityFlags")).toSet())
   require(j.getString("bundleId")==b.bundleId);return b
  }
  fun project(d:DriveRecord,user:String,device:String,run:String,catalog:List<Track>,revision:Long=1,
   recommendations:Map<String,String> = emptyMap(),excluded:Set<String> = emptySet(),hearts:List<Pin> = emptyList()):ReviewSyncBundle {
   require(d.startedAt!=null&&d.endedAt!=null&&d.durationMs!=null)
   return ReviewSyncBundle(user,device,run,d.id,d.key,revision,d.endedAt,d.startedAt,d.endedAt,java.time.ZoneId.systemDefault().id,
    d.tracks.distinct().map{id->catalog.singleOrNull{it.id==id} ?: Track(id,id,"메타데이터 미제공")},
    d.playbacks.map{ReviewOccurrence(it.playbackSessionId,it.musicId,it.creditedWallMs,it.uniqueListenedMediaMs,it.uniqueListeningRate,it.confidence,it.qualityFlags,recommendations[it.playbackSessionId])},
    excluded,hearts.filter{it.sourceDriveKey==d.key&&it.track!=null}.map{ReviewHeartData(it.id,it.track!!.id,it.savedAt,it.tags,it.note,it.deletedAt)},d.testData,d.qualityFlags)
  }
 }
}
