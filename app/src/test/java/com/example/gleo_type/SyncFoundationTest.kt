package com.example.gleo_type

import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncFoundationTest {
 private fun root()=Files.createTempDirectory("gleo-sync-test").toFile()
 private fun bundle(revision:Long=1)=ReviewSyncBundle("car-user","car-device","run","drive","run/drive",revision,
  "2026-11-13T00:02:00Z","2026-11-13T00:00:00Z","2026-11-13T00:01:00Z","Asia/Seoul",
  listOf(Track("a","A","artist"),Track("b","B","artist")),
  listOf(ReviewOccurrence("pb-a1","a",1000.0,1000.0,.1,"lower_bound",emptySet(),"rec-a"),ReviewOccurrence("pb-a2","a",500.0,400.0,.05,"lower_bound",emptySet()),ReviewOccurrence("pb-b","b",200.0,null,null,null,setOf("missing_summary"))),testData=true)
 @Test fun canonicalBundleKeepsOrderedOccurrencesAndNoAudioOrGuessedIds() {
  val b=bundle();val decoded=ReviewSyncBundle.decode(b.encode());assertEquals(b,decoded)
  assertEquals(listOf("pb-a1","pb-a2","pb-b"),decoded.occurrences.map{it.playbackSessionId})
  assertEquals("rec-a",decoded.occurrences.first().recommendationId);assertNull(decoded.occurrences[1].recommendationId)
  assertFalse(b.encode().contains("mp3"));assertEquals(b.contentHash,decoded.contentHash)
  assertNotEquals(b.bundleId,b.copy(sourceDeviceId="other").bundleId)
 }
 @Test fun queueSurvivesRestartRetryAndRejectsLateAcknowledgement() {
  val f=File(root(),"queue");val first=DurableSyncOutbox(f);val id=first.enqueue(bundle());assertEquals(id,first.enqueue(bundle()))
  assertEquals(1,first.states().size);val lease=first.claim(100,50)!!
  val reopened=DurableSyncOutbox(f);assertEquals(OutboxState.in_flight,reopened.states()[id]);assertNull(reopened.claim(149))
  val recovered=reopened.claim(150)!!;assertEquals(2,recovered.attempt);assertFalse(first.acknowledge(lease))
  assertTrue(reopened.fail(recovered,"offline",300));assertEquals(OutboxState.retry_scheduled,first.states()[id]);assertNull(first.claim(299))
  val retry=first.claim(300)!!;assertTrue(first.fail(retry,"no transport"));assertEquals(OutboxState.failed,first.states()[id])
  first.scheduleRetry(id,400);val final=first.claim(400)!!;assertTrue(first.acknowledge(final))
  assertEquals(OutboxState.acknowledged,DurableSyncOutbox(f).states()[id]);assertNull(first.claim(1000))
 }
 @Test fun duplicateOutboxRevisionConflictAndBrokenTailPreserveOriginal() {
  val f=File(root(),"queue");val q=DurableSyncOutbox(f);q.enqueue(bundle());val prior=f.readBytes()
  assertTrue(runCatching{q.enqueue(bundle().copy(excludedMusicIds=setOf("a")))}.isFailure);assertArrayEquals(prior,f.readBytes())
  f.appendText("{broken");val broken=f.readBytes();assertTrue(runCatching{DurableSyncOutbox(f).states()}.isFailure);assertArrayEquals(broken,f.readBytes())
 }
 @Test fun durableInboxScopesPeersRevisionsAndRestoresSelection() {
  val r=root();val identity=SyncIdentityRepository(File(r,"identity"),"phone-user")
  val device=identity.deviceId();assertEquals(device,SyncIdentityRepository(File(r,"identity"),"phone-user").deviceId())
  val inbox=DurableReviewInbox(File(r,"inbox"),"phone-user",identity);val b=bundle()
  assertEquals(SyncAcceptance.WRONG_USER,inbox.accept(b.encode()));identity.authorizePeer("car-user","car-device")
  assertEquals(SyncAcceptance.APPLIED,inbox.accept(b.encode()));assertEquals(SyncAcceptance.DUPLICATE,inbox.accept(b.encode()))
  inbox.setExcluded(b.bundleId,setOf("a"));val reopened=DurableReviewInbox(File(r,"inbox"),"phone-user",identity)
  assertEquals(setOf("a"),reopened.excluded(b.bundleId));assertEquals(SyncAcceptance.CONFLICT,reopened.accept(b.copy(excludedMusicIds=setOf("b")).encode()))
  assertEquals(SyncAcceptance.APPLIED,reopened.accept(bundle(2).copy(updatedAt="2020-01-01T00:00:00Z").encode()))
  assertEquals(SyncAcceptance.STALE,reopened.accept(b.encode()));assertEquals(setOf("a"),reopened.excluded(b.bundleId))
  val record=reopened.history().refresh().drives.single();assertEquals(b.driveSessionId,record.id);assertEquals(3,record.playbacks.size)
  assertNull(record.place);assertTrue(record.testData);assertTrue(record.key.startsWith("sync/"))
  identity.revokePeer("car-user","car-device");assertTrue(reopened.bundles().isEmpty())
 }
 @Test fun malformedBundleAndUnconfirmedDriveNeverBecomeFinishedReview() {
  assertTrue(runCatching{ReviewSyncBundle.decode("{}")} .isFailure)
  assertTrue(runCatching{ReviewSyncBundle.decode(bundle().json().put("audioBytes","not_allowed").toString())}.isFailure)
  assertTrue(runCatching{bundle().copy(endedAt="2025-01-01T00:00:00Z")} .isFailure)
  assertTrue(runCatching{ReviewSyncBundle.project(DriveRecord("r/d","d",null,null,emptyList(),0.0,false,null,true),"u","s","r",emptyList())}.isFailure)
  assertTrue(runCatching{bundle().copy(occurrences=listOf(bundle().occurrences.first(),bundle().occurrences.first()))}.isFailure)
 }
 @Test fun concurrentEnqueueProducesOneOperation() {
  val f=File(root(),"queue");val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
  try{val ids=(1..12).map{executor.submit<String>{DurableSyncOutbox(f).enqueue(bundle())}}.map{it.get()};assertEquals(1,ids.toSet().size);assertEquals(1,DurableSyncOutbox(f).states().size)}finally{executor.shutdown()}
 }
}
