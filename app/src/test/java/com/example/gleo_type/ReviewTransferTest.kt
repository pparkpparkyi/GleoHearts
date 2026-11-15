package com.example.gleo_type

import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Encrypted local loopback, not a network or a second physical device. */
class ReviewTransferTest {
 private val key=ByteArray(32){it.toByte()}
 private fun bundle(revision:Long=1)=ReviewSyncBundle("car-user","car-device","run","drive","run/drive",revision,
  "2026-11-14T00:02:00Z","2026-11-14T00:00:00Z","2026-11-14T00:01:00Z","Asia/Seoul",
  listOf(Track("a","synthetic","artist")),listOf(ReviewOccurrence("pb","a",100.0,100.0,.1,"lower_bound",emptySet())),testData=true)
 private fun peer(reverse:Boolean=false,k:ByteArray=key)=if(reverse)ReviewPeer("phone-user","phone-device","car-user","car-device",k) else ReviewPeer("car-user","car-device","phone-user","phone-device",k)
 @Test fun encryptedTransferAckLossRetryAndRestartAreIdempotent() {
  val r=Files.createTempDirectory("sync-transfer").toFile();val identity=SyncIdentityRepository(File(r,"identity"),"phone-user")
  identity.authorizePeer("car-user","car-device");val inbox=DurableReviewInbox(File(r,"inbox"),"phone-user",identity)
  val queue=DurableSyncOutbox(File(r,"queue"));val b=bundle();queue.enqueue(b)
  val receiver=ReviewBundleReceiver(peer(true),inbox)
  val failAfterCommit=ReviewExchange{wire->receiver.receive(wire);throw java.io.IOException("synthetic ACK loss")}
  assertEquals(TransferOutcome.RETRY_SCHEDULED,ReviewBundleDispatcher(queue,peer(),failAfterCommit).sendOne(100))
  assertEquals(1,inbox.bundles().size);inbox.setExcluded(b.bundleId,setOf("a"))
  val reopened=DurableSyncOutbox(File(r,"queue"));assertEquals(TransferOutcome.IDLE,ReviewBundleDispatcher(reopened,peer(),ReviewExchange(receiver::receive)).sendOne(1099))
  assertEquals(TransferOutcome.ACKNOWLEDGED,ReviewBundleDispatcher(reopened,peer(),ReviewExchange(receiver::receive)).sendOne(1100))
  assertEquals(setOf("a"),DurableReviewInbox(File(r,"inbox"),"phone-user",identity).excluded(b.bundleId))
  assertEquals(1,inbox.bundles().size);assertEquals(OutboxState.acknowledged,reopened.states().values.single())
  queue.enqueue(bundle(2));assertEquals(TransferOutcome.ACKNOWLEDGED,ReviewBundleDispatcher(queue,peer(),ReviewExchange(receiver::receive)).sendOne(1200))
  assertEquals(2L,inbox.bundles().single().revision);assertEquals(setOf("a"),inbox.excluded(b.bundleId))
  assertEquals(SyncAcceptance.STALE,inbox.accept(b.encode()))
 }
 @Test fun noAutomaticPeerTrustWrongKeyTargetAndTamperingAreRejected() {
  val r=Files.createTempDirectory("sync-auth").toFile();val identity=SyncIdentityRepository(File(r,"identity"),"phone-user")
  val inbox=DurableReviewInbox(File(r,"inbox"),"phone-user",identity)
  val body=JSONObject().put("synthetic","not a bundle")
  val wire=peer().seal("review",body)
  assertFalse(wire.contains("not a bundle"))
  assertTrue(runCatching{peer(true,ByteArray(32){99}).open(wire,"review")}.isFailure)
  assertTrue(runCatching{peer().open(wire,"review")}.isFailure)
  val corrupt=JSONObject(wire);corrupt.put("ciphertext",corrupt.getString("ciphertext").reversed())
  assertTrue(runCatching{peer(true).open(corrupt.toString(),"review")}.isFailure)
  assertEquals(SyncAcceptance.WRONG_USER,inbox.accept(bundle().encode()));assertTrue(inbox.bundles().isEmpty())
  val wrongInbox=DurableReviewInbox(File(r,"wrong-inbox"),"different-local-user",SyncIdentityRepository(File(r,"wrong-id"),"different-local-user"))
  assertTrue(runCatching{ReviewBundleReceiver(peer(true),wrongInbox)}.isFailure)
 }
 @Test fun forgedAckNeverAcknowledgesAndOriginalQueueIsRetained() {
  val r=Files.createTempDirectory("sync-ack").toFile();val q=DurableSyncOutbox(File(r,"queue"));q.enqueue(bundle())
  val link=ReviewExchange{peer(true).seal("ack",JSONObject().put("operationId","wrong"))}
  assertEquals(TransferOutcome.RETRY_SCHEDULED,ReviewBundleDispatcher(q,peer(),link).sendOne(0))
  assertEquals(OutboxState.retry_scheduled,q.states().values.single())
 }
}
