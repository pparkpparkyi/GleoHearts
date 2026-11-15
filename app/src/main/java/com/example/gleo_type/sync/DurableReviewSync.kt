package com.example.gleo_type.sync

import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

enum class OutboxState { pending, in_flight, acknowledged, failed, retry_scheduled }
data class SyncLease(val operationId:String,val token:String,val bundle:ReviewSyncBundle,val attempt:Int)
data class LocalReviewDecision(val bundleId:String,val localUserId:String,val revision:Long,val updatedAt:String?,
 val excludedMusicIds:Set<String>,val savedArchiveIds:List<String>,val savedForSourceRevision:Long?)
class DurableSyncOutbox(file:File) {
 private val journal=SyncJournal(file)
 private fun rows(j:JSONObject)=j.optJSONObject("rows") ?: JSONObject().also{j.put("rows",it)}
 fun enqueue(bundle:ReviewSyncBundle):String=journal.update{j->
  val r=rows(j);val id=SyncJson.id(bundle.bundleId,bundle.revision.toString(),bundle.contentHash)
  r.keys().forEach{k->val old=r.getJSONObject(k);val b=ReviewSyncBundle.decode(old.getString("payload"));
   require(b.bundleId!=bundle.bundleId||b.revision!=bundle.revision||b.contentHash==bundle.contentHash){"Conflicting bundle revision"}}
  if(!r.has(id)){require(r.length()<1000);r.put(id,JSONObject().put("payload",bundle.encode()).put("state",OutboxState.pending.name).put("attempt",0))};id
 }
 fun claim(nowEpochMs:Long,leaseMs:Long=30000):SyncLease?=journal.update{j->
  require(nowEpochMs>=0&&leaseMs in 1..300000)
  val r=rows(j);val id=r.keys().asSequence().sorted().firstOrNull{k->val x=r.getJSONObject(k)
   when(OutboxState.valueOf(x.getString("state"))){
    OutboxState.pending->true
    OutboxState.retry_scheduled->x.getLong("retryAt")<=nowEpochMs
    OutboxState.in_flight->x.getLong("leaseUntil")<=nowEpochMs
    else->false
   }} ?: return@update null
  val x=r.getJSONObject(id);val token=UUID.randomUUID().toString();val attempt=x.getInt("attempt")+1
  x.put("state",OutboxState.in_flight.name).put("token",token).put("attempt",attempt).put("leaseUntil",Math.addExact(nowEpochMs,leaseMs))
  SyncLease(id,token,ReviewSyncBundle.decode(x.getString("payload")),attempt)
 }
 fun acknowledge(lease:SyncLease)=finish(lease,null,null)
 fun fail(lease:SyncLease,reason:String,retryAtEpochMs:Long?=null)=finish(lease,reason,retryAtEpochMs)
 private fun finish(lease:SyncLease,error:String?,retryAt:Long?):Boolean=journal.update{j->
  val x=rows(j).optJSONObject(lease.operationId) ?: return@update false
  if(x.getString("state")!=OutboxState.in_flight.name||x.optString("token")!=lease.token)return@update false
  if(retryAt!=null)require(retryAt>=0)
  x.put("state",if(error==null)OutboxState.acknowledged.name else if(retryAt==null)OutboxState.failed.name else OutboxState.retry_scheduled.name)
  if(error!=null)x.put("error",error.take(256));if(retryAt!=null)x.put("retryAt",retryAt)
  true
 }
 fun scheduleRetry(operationId:String,atEpochMs:Long) {require(atEpochMs>=0);journal.update{j->val x=rows(j).getJSONObject(operationId)
  require(x.getString("state")==OutboxState.failed.name);x.put("state",OutboxState.retry_scheduled.name).put("retryAt",atEpochMs)}}
 fun states():Map<String,OutboxState> = journal.read{j->val r=rows(j);r.keys().asSequence().associateWith{OutboxState.valueOf(r.getJSONObject(it).getString("state"))}}
 fun isCurrent(operationId:String,token:String):Boolean=journal.read{j->val row=rows(j).optJSONObject(operationId)
  row!=null && row.optString("state")==OutboxState.in_flight.name && row.optString("token")==token}
}

/** Original source IDs stay in the inbox. No incoming events are appended to the local SDK journal. */
class DurableReviewInbox(file:File,private val localUserId:String,private val identity:SyncIdentityRepository) {
 val ownerId get()=localUserId
 init {require(identity.ownerId==localUserId)}
 private val journal=SyncJournal(file)
 private fun rows(j:JSONObject)=j.optJSONObject("bundles") ?: JSONObject().also{j.put("bundles",it)}
 fun accept(payload:String):SyncAcceptance {
  val b=ReviewSyncBundle.decode(payload)
  if(!identity.accepts(b.localUserId,b.sourceDeviceId))return SyncAcceptance.WRONG_USER
  return journal.update{j->val r=rows(j);val old=r.optJSONObject(b.bundleId)?.getString("payload")?.let(ReviewSyncBundle::decode)
   if(old!=null){if(b.revision<old.revision)return@update SyncAcceptance.STALE
    if(b.revision==old.revision)return@update if(b.contentHash==old.contentHash)SyncAcceptance.DUPLICATE else SyncAcceptance.CONFLICT}
   require(r.length()<1000||r.has(b.bundleId));val item=r.optJSONObject(b.bundleId) ?: JSONObject()
   item.put("payload",b.encode());r.put(b.bundleId,item);SyncAcceptance.APPLIED
  }
 }
 fun bundles():List<ReviewSyncBundle> = journal.read{j->val r=rows(j);r.keys().asSequence().map{ReviewSyncBundle.decode(r.getJSONObject(it).getString("payload"))}
  .filter{identity.accepts(it.localUserId,it.sourceDeviceId)}.toList()}
 private fun get(id:String)=bundles().single{it.bundleId==id}
 fun excluded(id:String):Set<String> {val b=get(id);return journal.read{j->val a=rows(j).getJSONObject(id).optJSONArray("localExcluded")
  (if(a==null)b.excludedMusicIds else (0 until a.length()).map{a.getString(it)}.toSet()).intersect(b.tracks.map{it.id}.toSet())}}
 fun setExcluded(id:String,excluded:Set<String>) {val b=get(id);require(excluded.all{x->b.tracks.any{it.id==x}})
  journal.update{j->val item=rows(j).getJSONObject(id);val before=item.optJSONArray("localExcluded")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()} ?: b.excludedMusicIds
   if(before!=excluded)item.put("localExcluded",JSONArray(excluded.sorted())).put("decisionRevision",item.optLong("decisionRevision",0)+1).put("localUpdatedAt",java.time.Instant.now().toString())}}
 fun history():DriveHistoryRepository=object:DriveHistoryRepository{override fun refresh()=HistorySnapshot(bundles().map{it.driveRecord()})}
 /** Bridge for the existing Review onSave callback. Receipt only after actual existing archive save. */
 fun saveHearts(id:String,archives:ArchiveRepository,at:String):List<Pin> {
  val b=get(id);val included=b.tracks.map{it.id}.filterNot{it in excluded(id)}
  require(included.isNotEmpty())
  val saved=archives.saveReview(b.driveRecord(),b.tracks,included,localUserId,b.zoneId,at)
  journal.update{j->val item=rows(j).getJSONObject(id);val signature=SyncJson.id(b.contentHash,included.joinToString("\n"),saved.joinToString("\n"){it.id})
   if(item.optString("saveSignature")!=signature)item.put("decisionRevision",item.optLong("decisionRevision",0)+1).put("localUpdatedAt",at).put("saveSignature",signature)
   item.put("savedArchiveIds",JSONArray(saved.map{it.id})).put("savedSelection",JSONArray(included))
    .put("savedForRevision",b.revision).put("savedSourceHash",b.contentHash)}
  return saved
 }
 fun savedArchiveIds(id:String):List<String> {get(id);return journal.read{j->val a=rows(j).getJSONObject(id).optJSONArray("savedArchiveIds") ?: JSONArray();(0 until a.length()).map{a.getString(it)}}}
 fun decision(id:String):LocalReviewDecision {get(id);return journal.read{j->val item=rows(j).getJSONObject(id)
  LocalReviewDecision(id,localUserId,item.optLong("decisionRevision",0),SyncJson.optional(item,"localUpdatedAt"),excluded(id),savedArchiveIds(id),
   if(item.has("savedForRevision"))item.getLong("savedForRevision") else null)}}
}

/** Local-only post-flush preparation. No transport, automatic ACK, account or uploaded payload. */
class DriveSyncPreparation(private val root:File,private val user:String,private val run:String) {
 val identity by lazy{SyncIdentityRepository(File(root,"sync/v1/identity.jsonl"),user)}
 val outbox by lazy{DurableSyncOutbox(File(root,"sync/v1/outbox.jsonl"))}
 fun prepare(drive:String):String? {
  val record=JsonlHistoryRepository(File(root,"events"),user).refresh().drives.singleOrNull{it.key=="$run/$drive"} ?: return null
  if(record.endedAt==null||record.durationMs==null)return null
  val ledger=File(root,"ai-pipeline/v1/ledger.jsonl")
  val recommendations=if(ledger.isFile)runCatching{com.example.gleo_type.context.PipelineLedger(ledger).records()
   .filter{it.kind==com.example.gleo_type.context.LedgerKind.PLAYBACK_BOUND&&it.ids.localUserId==user&&it.ids.appRunId==run&&it.ids.driveSessionId==drive}
   .mapNotNull{r->r.ids.playbackSessionId?.let{pb->r.ids.recommendationId?.let{pb to it}}}.toMap()}.getOrDefault(emptyMap()) else emptyMap()
  val hearts=LocalArchiveRepository(File(root,"archive")).load().filter {
   it.sourceDriveKey==record.key && it.track?.id in record.tracks &&
    (it.runtimeHeart?.localUserId==user || it.heartsReview?.localUserId==user)
  }
  return outbox.enqueue(ReviewSyncBundle.project(record,user,identity.deviceId(),run,
   hearts.mapNotNull{it.track}.distinctBy{it.id},recommendations=recommendations,hearts=hearts))
 }
}
