package com.example.gleo_type

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Developer-authorized USB/ADB peer fixture, NOT product pairing or a CAR device. Synthetic only. */
class Step8dUsbPeerTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private fun awaitFile(file:File):File {val until=android.os.SystemClock.elapsedRealtime()+90000
  while(android.os.SystemClock.elapsedRealtime()<until){if(file.isFile&&file.length()>0)return file;Thread.sleep(50)}
  error("USB fixture file timeout: ${file.name}")}
 @Test fun authenticatedPcUsbFilesReachReviewAndDurableAckInBothDirections() {
  assumeTrue("Explicit developer USB fixture required",InstrumentationRegistry.getArguments().getString("gleo.usbFixture")=="YES")
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(c.cacheDir,"step8d-usb-${java.util.UUID.randomUUID()}").apply{mkdirs()}
  val pairing=ReviewDevicePairing("SYNTHETIC_PHONE_USER","SYNTHETIC_PHONE_DEVICE")
  File(root,"phone-offer.json").writeText(pairing.offer)
  File(c.cacheDir,"step8d-usb-pointer.txt").writeText(root.name)
  val remote=awaitFile(File(root,"pc-offer.json")).readText()
  val fingerprint=pairing.fingerprint(remote)
  File(root,"phone-check.txt").writeText(fingerprint)
  // Test-only trusted USB/ADB channel confirms both fingerprints. No automatic production trust.
  val confirmed=awaitFile(File(root,"pc-confirm.txt")).readText().trim();assertEquals(fingerprint,confirmed)
  val peer=pairing.confirm(remote,confirmed)
  val identity=SyncIdentityRepository(File(root,"identity"),"SYNTHETIC_PHONE_USER")
  identity.authorizePeer("SYNTHETIC_PC_USER","SYNTHETIC_PC_DEVICE")
  val inbox=DurableReviewInbox(File(root,"inbox"),"SYNTHETIC_PHONE_USER",identity)
  val receiver=ReviewBundleReceiver(peer,inbox)
  ReviewFileTransfer.receiveFile(awaitFile(File(root,"pc-request.gleo")),File(root,"phone-ack.gleo"),receiver)
  ReviewFileTransfer.receiveFile(awaitFile(File(root,"pc-request-retry.gleo")),File(root,"phone-ack-retry.gleo"),receiver)
  assertEquals(1,inbox.bundles().size)
  val received=inbox.bundles().single();assertTrue(received.testData)
  val queue=DurableSyncOutbox(File(root,"outbox"))
  val outgoing=received.copy(localUserId="SYNTHETIC_PHONE_USER",sourceDeviceId="SYNTHETIC_PHONE_DEVICE",appRunId="PHONE_SYNTHETIC_RUN")
  queue.enqueue(outgoing)
  val sender=ReviewFileTransfer(File(root,"transfer"),queue,peer)
  ReviewFileTransfer.writeNew(File(root,"phone-request.gleo"),sender.prepare(System.currentTimeMillis())!!)
  assertTrue(sender.acceptAck(awaitFile(File(root,"pc-ack.gleo")).readText()))
  assertFalse(sender.acceptAck(File(root,"pc-ack.gleo").readText()))
  assertEquals(OutboxState.acknowledged,queue.states().values.single())
  val archive=LocalArchiveRepository(File(root,"archive"))
  ui.setContent{ReviewFinalTheme{ReviewFinalBackground{Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
   ReceivedReviewPanel(received.driveRecord(),inbox,archive,{},{})
  }}}}
  ui.waitUntil(10000){ui.onAllNodesWithTag("mobile_review_PC_SYNTH_A").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("mobile_review_PC_SYNTH_A").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),450)}
  ui.waitUntil(10000){inbox.excluded(received.bundleId)==setOf("PC_SYNTH_A")}
  ui.onNodeWithTag("mobile_review_remember").performClick();ui.waitUntil(10000){archive.load().size==1}
  ui.onNodeWithTag("mobile_review_undo").performScrollTo().performClick()
  ui.waitUntil(10000){inbox.excluded(received.bundleId).isEmpty()}
  ui.onNodeWithTag("mobile_review_remember").performClick();ui.waitUntil(10000){archive.load().size==2}
  ui.waitUntil(10000){ui.onAllNodesWithText("저장 중").fetchSemanticsNodes().isEmpty()}
  File(root,"result.json").writeText(JSONObject().put("scope","PC_GALAXY_USB_SYNTHETIC_NOT_CAR_OR_PRODUCT_PAIRING")
   .put("receivedBundles",inbox.bundles().size).put("heartsCount",archive.load().size).put("outboxState",queue.states().values.single().name)
   .put("duplicateAckApplied",false).put("pid",android.os.Process.myPid()).toString(2))
  InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{b->File(root,"review.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 }
}

class Step8dUsbPeerRestartTest {
 @Test fun receivedUsbDataAndAckRestoreInDifferentAndroidProcess() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("gleo.usbFixture")=="YES")
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val name=File(c.cacheDir,"step8d-usb-pointer.txt").readText().trim();require(name.matches(Regex("step8d-usb-[a-f0-9-]{36}")))
  val root=File(c.cacheDir,name);val proof=JSONObject(File(root,"result.json").readText())
  assertNotEquals(proof.getInt("pid"),android.os.Process.myPid())
  val identity=SyncIdentityRepository(File(root,"identity"),"SYNTHETIC_PHONE_USER")
  val inbox=DurableReviewInbox(File(root,"inbox"),"SYNTHETIC_PHONE_USER",identity)
  assertEquals(1,inbox.bundles().size);assertEquals(2,inbox.savedArchiveIds(inbox.bundles().single().bundleId).size)
  assertEquals(2,LocalArchiveRepository(File(root,"archive")).load().size)
  assertEquals(OutboxState.acknowledged,DurableSyncOutbox(File(root,"outbox")).states().values.single())
 }
}
