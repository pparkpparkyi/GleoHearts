package com.example.gleo_type

import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReviewFilePairingTest {
 @Test fun explicitMatchingFingerprintEnablesEncryptedFileRoundtripAndRestartedQueue() {
  val a=ReviewDevicePairing("car-user","car-device");val b=ReviewDevicePairing("phone-user","phone-device")
  val check=a.fingerprint(b.offer);assertEquals(check,b.fingerprint(a.offer))
  assertTrue(runCatching{a.confirm(b.offer,"NOT_CONFIRMED")}.isFailure)
  val source=a.confirm(b.offer,check);val target=b.confirm(a.offer,check)
  val root=Files.createTempDirectory("file-peer").toFile();val identity=SyncIdentityRepository(File(root,"identity"),"phone-user")
  val inbox=DurableReviewInbox(File(root,"inbox"),"phone-user",identity)
  val bundle=ReviewSyncBundle("car-user","car-device","run","drive","run/drive",1,
   "2026-11-14T00:02:00Z","2026-11-14T00:00:00Z","2026-11-14T00:01:00Z","Asia/Seoul",
   listOf(Track("a","synthetic","artist")),listOf(ReviewOccurrence("pb","a",100.0,100.0,.1,"lower_bound",emptySet())),testData=true)
  val outbox=DurableSyncOutbox(File(root,"queue"));val op=outbox.enqueue(bundle)
  val sender=ReviewFileTransfer(File(root,"transfer"),outbox,source);val wire=sender.prepare(100)!!
  val request=File(root,"request.gleo");val ack=File(root,"ack.gleo")
  ReviewFileTransfer.writeNew(request,wire)
  val receiver=ReviewBundleReceiver(target,inbox)
  assertTrue(runCatching{ReviewFileTransfer.receiveFile(request,ack,receiver)}.isFailure);assertFalse(ack.exists())
  identity.authorizePeer("car-user","car-device") // Only after explicit out-of-band confirmation.
  ReviewFileTransfer.receiveFile(request,ack,receiver)
  assertEquals(1,inbox.bundles().size);assertEquals(OutboxState.in_flight,outbox.states()[op])
  val restarted=ReviewFileTransfer(File(root,"transfer"),DurableSyncOutbox(File(root,"queue")),source)
  assertEquals(wire,restarted.prepare(110));assertTrue(restarted.acceptAck(ack.readText()))
  assertFalse(restarted.acceptAck(ack.readText()));assertEquals(OutboxState.acknowledged,outbox.states()[op])
  assertTrue(runCatching{ReviewFileTransfer.writeNew(request,wire)}.isFailure)
 }
 @Test fun changedOfferFingerprintAndWrongPeerCannotBeConfirmed() {
  val a=ReviewDevicePairing("u","a");val b=ReviewDevicePairing("u","b");val attacker=ReviewDevicePairing("u","b")
  val expected=a.fingerprint(b.offer);assertNotEquals(expected,a.fingerprint(attacker.offer))
  assertTrue(runCatching{a.confirm(attacker.offer,expected)}.isFailure)
  assertTrue(runCatching{a.fingerprint(a.offer)}.isFailure)
 }
 @Test fun externalLeaseReplacementCannotReuseOldStagedRequest() {
  val a=ReviewDevicePairing("u","source");val b=ReviewDevicePairing("u","target")
  val peer=a.confirm(b.offer,a.fingerprint(b.offer));val root=Files.createTempDirectory("lease-fence").toFile()
  val q=DurableSyncOutbox(File(root,"queue"))
  val bundle=ReviewSyncBundle("u","source","run","drive","run/drive",1,"2026-11-14T00:02:00Z","2026-11-14T00:00:00Z","2026-11-14T00:01:00Z","UTC",
   listOf(Track("a","synthetic","artist")),listOf(ReviewOccurrence("pb","a",100.0,100.0,.1,"lower_bound",emptySet())),testData=true)
  q.enqueue(bundle);val file=ReviewFileTransfer(File(root,"transfer"),q,peer);assertNotNull(file.prepare(0))
  val replaced=q.claim(300000)!!;assertTrue(q.isCurrent(replaced.operationId,replaced.token))
  assertNull(file.prepare(300001));assertFalse(file.retry(300002));assertTrue(q.isCurrent(replaced.operationId,replaced.token))
 }
}
