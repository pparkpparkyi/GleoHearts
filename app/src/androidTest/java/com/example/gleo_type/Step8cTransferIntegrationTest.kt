package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.recommendation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** One Galaxy/cache pair simulation. No peer/transport, server, production identity or audio copy. */
class Step8cTransferIntegrationTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 @Test fun encryptedLoopbackRetriesAckLossAndOpensExistingReceivedReview() {
  val root=File(ui.activity.cacheDir,"step8c-sync-${UUID.randomUUID()}").apply{mkdirs()}
  val identity=SyncIdentityRepository(File(root,"identity"),"SYNTHETIC_PHONE");identity.deviceId();identity.authorizePeer("SYNTHETIC_CAR","SYNTHETIC_DEVICE")
  val inbox=DurableReviewInbox(File(root,"inbox"),"SYNTHETIC_PHONE",identity);val queue=DurableSyncOutbox(File(root,"outbox"))
  val tracks=listOf(Track("SYNTHETIC_A","합성 주행 음악 A","격리 데이터"),Track("SYNTHETIC_B","합성 주행 음악 B","격리 데이터"))
  val bundle=ReviewSyncBundle("SYNTHETIC_CAR","SYNTHETIC_DEVICE","SYNTHETIC_RUN","SYNTHETIC_DRIVE","SYNTHETIC_RUN/SYNTHETIC_DRIVE",1,
   "2026-11-13T00:02:00Z","2026-11-13T00:00:00Z","2026-11-13T00:01:00Z","Asia/Seoul",tracks,
   listOf(ReviewOccurrence("SYNTHETIC_PB_A","SYNTHETIC_A",1000.0,1000.0,.1,"incomplete_lower_bound",emptySet(),"SYNTHETIC_REC"),
    ReviewOccurrence("SYNTHETIC_PB_B","SYNTHETIC_B",1000.0,null,null,null,setOf("missing_duration"))),testData=true)
  val payload=bundle.encode();val op=queue.enqueue(bundle)
  val key=ByteArray(32).also{java.security.SecureRandom().nextBytes(it)}
  val source=ReviewPeer("SYNTHETIC_CAR","SYNTHETIC_DEVICE","SYNTHETIC_PHONE",identity.deviceId(),key)
  val target=ReviewPeer("SYNTHETIC_PHONE",identity.deviceId(),"SYNTHETIC_CAR","SYNTHETIC_DEVICE",key)
  val receiver=ReviewBundleReceiver(target,inbox)
  val broken=ReviewExchange{request->assertFalse(request.contains("합성 주행 음악"));receiver.receive(request);throw java.io.IOException("ISOLATED_ACK_LOSS")}
  assertEquals(TransferOutcome.RETRY_SCHEDULED,ReviewBundleDispatcher(queue,source,broken).sendOne(100))
  assertEquals(1,inbox.bundles().size)
  val reopened=DurableSyncOutbox(File(root,"outbox"))
  assertEquals(TransferOutcome.ACKNOWLEDGED,ReviewBundleDispatcher(reopened,source,ReviewExchange(receiver::receive)).sendOne(1100))
  assertEquals(SyncAcceptance.DUPLICATE,inbox.accept(payload))
  val archives=LocalArchiveRepository(File(root,"archive"));val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
  val restore=StateRestorationTester(ui)
  restore.setContent{ReviewFinalTheme{ReviewFinalBackground{Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
   ReceivedReviewPanel(bundle.driveRecord(),inbox,archives,{},{})
  }}}}
  ui.waitUntil(10000){ui.onAllNodesWithTag("mobile_review_SYNTHETIC_A").fetchSemanticsNodes().isNotEmpty()}

  ui.onNodeWithTag("mobile_review_SYNTHETIC_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),450)}
  ui.waitUntil(10000){inbox.excluded(bundle.bundleId)==setOf("SYNTHETIC_A")}
  restore.emulateSavedInstanceStateRestore();ui.onNodeWithTag("mobile_review_SYNTHETIC_A").assertDoesNotExist()
  ui.onNodeWithTag("mobile_review_remember").performClick();ui.waitUntil(10000){archives.load().size==1}
  val b=archives.load().single();assertEquals("SYNTHETIC_B",b.track!!.id);archives.save(b.copy(note="SYNTHETIC_NOTE_TO_KEEP"))
  ui.onNodeWithTag("mobile_review_undo").performScrollTo().performClick()
  ui.waitUntil(10000){inbox.excluded(bundle.bundleId).isEmpty()}
  ui.onNodeWithTag("mobile_review_remember").performClick();ui.waitUntil(10000){archives.load().size==2}
  val again=DurableReviewInbox(File(root,"inbox"),"SYNTHETIC_PHONE",identity)
  again.saveHearts(bundle.bundleId,archives,"2026-11-13T00:04:00Z")
  assertEquals(2,archives.load().size);assertEquals("SYNTHETIC_NOTE_TO_KEEP",archives.load().single{it.id==b.id}.note)
  assertEquals(payload,again.bundles().single().encode());assertEquals(2,again.savedArchiveIds(bundle.bundleId).size)
  assertEquals(OutboxState.acknowledged,queue.states()[op]);assertNull(failure.get())
  ui.waitUntil(10000){ui.onAllNodesWithText("저장 중").fetchSemanticsNodes().isEmpty()}
  ui.onNodeWithTag("mobile_review_result").assertExists()
  val capture=File(ui.activity.cacheDir,"step8c-captures").apply{mkdirs()}
  InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->File(capture,"received-review-hearts-saved.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  File(root,"proof.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("bundleId",bundle.bundleId).put("source","ENCRYPTED_LOCAL_LOOPBACK_NOT_DEVICE_SYNC").toString())
  File(ui.activity.cacheDir,"step8c-sync-root.txt").writeText(root.absolutePath)
 }
 @Test fun installedMissingModelFallsBackWithoutChangingUserJournal() {
  val context=ui.activity;val path=File(context.filesDir,"models/clap500/manifest.json")
  org.junit.Assume.assumeFalse("Only meaningful before explicit operational model installation",path.exists())
  val model=RecommendationModelAdapter(context.filesDir,"SYNTHETIC_READONLY_CHECK")
  val run=model.recommend(listOf(Track("music_001","local","metadata")),MusicScope.THREE,emptySet(),emptySet(),0,null,null)
  assertEquals(RecommendationOrigin.TEST_CATALOG,run.origin);assertNull(run.contextEvidence);assertFalse(path.exists())
 }
}

class Step8cSyncRestartTest {
 @Test fun receivedReviewAndArchiveReceiptSurviveNewProcess() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(File(c.cacheDir,"step8c-sync-root.txt").readText()).canonicalFile
  require(root.toPath().startsWith(c.cacheDir.canonicalFile.toPath()))
  val proof=JSONObject(File(root,"proof.json").readText());assertNotEquals(proof.getInt("pid"),android.os.Process.myPid())
  val id=SyncIdentityRepository(File(root,"identity"),"SYNTHETIC_PHONE")
  val inbox=DurableReviewInbox(File(root,"inbox"),"SYNTHETIC_PHONE",id)
  assertTrue(inbox.excluded(proof.getString("bundleId")).isEmpty());assertEquals(2,inbox.savedArchiveIds(proof.getString("bundleId")).size)
  assertEquals(2,LocalArchiveRepository(File(root,"archive")).load().size)
  assertTrue(DurableSyncOutbox(File(root,"outbox")).states().values.all{it==OutboxState.acknowledged})
 }
}
