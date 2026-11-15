package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.context.LedgerKind
import com.example.gleo_type.music.RegistryPlaybackTestService
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.sync.*
import com.example.gleo_type.ui.app.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Media3 and operating archive APIs; cache stores, synthetic preference/photo/memo only.
 * Does NOT represent CAR transport, platform driving permission, GPS or human feedback. */
class Step13ArchiveAcceptanceTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private fun main(f:()->Unit)=i.runOnMainSync(f)
 private fun <T> request(f:()->ListenableFuture<T>):T {lateinit var pending:ListenableFuture<T>;main{pending=f()};return pending.get(30,TimeUnit.SECONDS)}
 private fun await(label:String,f:()->Boolean){val end=SystemClock.elapsedRealtime()+30000
  while(SystemClock.elapsedRealtime()<end){if(f())return;Thread.sleep(50)};fail(label)}
 private fun sha(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}

 @Test fun observedRecommendationPlaybackRuntimeHeartReviewAndMobileReadHaveOneIdentity() {
  val root=File(context.cacheDir,"step13-service-${UUID.randomUUID()}").apply{mkdirs()}
  RegistryPlaybackTestService.testRoot=root
  val component=ComponentName(context,RegistryPlaybackTestService::class.java)
  val browser=request{MediaBrowser.Builder(context,SessionToken(context,component)).buildAsync()}
  val c=RegistryPlaybackTestService.activeContainer!!
  lateinit var port:MediaPlaybackConnection
  val modelRoot=File(root,"isolated-model");val linked=File(modelRoot,"models/clap500").apply{mkdirs()}
  listOf("manifest.json","embeddings.bin").forEach{n->android.system.Os.symlink(File(context.filesDir,"models/clap500/$n").absolutePath,File(linked,n).absolutePath)}
  val adapter=RecommendationModelAdapter(modelRoot,c.localIdentity.id,PlaybackMappingVerifier(context)::verify,
   contextSnapshotProvider={c.contextSnapshots.capture(it)},contextRunId={c.runId},
   operationalRuntime=OperationalRecommendationRuntime(modelRoot,c.localIdentity.id,
    {context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}},
    {context.assets.open("audio-assets/catalog-v1.json").bufferedReader().use{it.readText()}}))
  main{c.startCollection();browser.volume=0f;port=MediaPlaybackConnection(context,c,component)}
  try {
   await("actual eleven-track catalog"){port.state.value.connected&&port.catalog.value.size==11}
   assertTrue(adapter.load());adapter.initialize(setOf("pop"),setOf("happy"))
   val drive=c.driveSessions.currentDriveSessionId()!!
   val recommendation=adapter.recommend(port.catalog.value,MusicScope.ALL,emptySet(),emptySet(),0,null,drive)
   assertEquals(RecommendationOrigin.AI_MODEL,recommendation.origin);assertEquals(listOf("jamendo_0010"),recommendation.musicIds)
   val track=port.catalog.value.single{it.id==recommendation.musicIds.single()}
   main{port.playRecommended(track,recommendation)}
   await("actual mapped Media3 playback and position"){port.state.value.playing&&port.state.value.position>=800&&port.state.value.playbackSessionId!=null}
   val pb=port.state.value.playbackSessionId!!
   c.causalWritesBarrier().get(10,TimeUnit.SECONDS)
   val bound=c.contextPipeline.ledger.records().single{it.kind==LedgerKind.PLAYBACK_BOUND&&it.ids.playbackSessionId==pb}
   assertEquals(recommendation.id,bound.ids.recommendationId);assertEquals(drive,bound.ids.driveSessionId);assertEquals(track.id,bound.ids.musicId)
   fun feedbackCount()=c.contextPipeline.ledger.records().count{it.kind==LedgerKind.LISTENING_FEEDBACK}
   val feedbackBefore=feedbackCount()
   fun remember():CarV10SaveResult {
    val result=AtomicReference<CarV10SaveResult?>();main{port.rememberCurrent{result.set(it)}}
    await("durable runtime Hearts save"){result.get()!=null};return result.get()!!
   }
   assertNotNull(remember().persistedReference)
   val runtime=c.runtimeArchives.load().single();assertEquals(runtime.id,remember().persistedReference)
   assertEquals(1,c.runtimeArchives.load().size);assertEquals(feedbackBefore,feedbackCount())
   val captured=runtime.runtimeHeart!!.captures.single()
   assertEquals(pb,captured.playbackSessionId);assertEquals(recommendation.id,captured.recommendationId)
   assertEquals(bound.ids.contextSnapshotId,JSONObject(captured.recommendationSnapshotJson!!).getString("contextSnapshotId"))
   assertEquals(track.id,runtime.track!!.id);assertNull(runtime.place)
   main{browser.stop()}
   await("stopped Media3 callback"){!port.state.value.playing}
   main{c.stopCollection()}
   runBlocking{withTimeout(15000){c.eventPublisher.awaitSubmittedWrites();c.awaitSyncPreparation()}}
   val history=JsonlHistoryRepository(File(root,"journal/events"),c.localIdentity.id)
   val record=history.refresh().drives.single{it.key=="${c.runId}/$drive"}
   assertNotNull(record.endedAt);assertTrue(record.playbacks.any{it.playbackSessionId==pb&&it.musicId==track.id})
   assertEquals(listOf(track.id),record.tracks)
   // The actual stop prepares an outbox whose occurrence carries the exact recommendation ID.
   val lease=checkNotNull(c.reviewSync.outbox.claim(System.currentTimeMillis()))
   assertEquals(recommendation.id,lease.bundle.occurrences.single{it.playbackSessionId==pb}.recommendationId)
   assertEquals(track.id,lease.bundle.hearts.single().musicId)
   assertTrue(c.reviewSync.outbox.fail(lease,"ISOLATED_NO_TRANSPORT",System.currentTimeMillis()+1000))
   val journal=File(root,"journal/events").listFiles()!!.associate{it.name to sha(it)}
   val ledgerHash=sha(File(root,"journal/ai-pipeline/v1/ledger.jsonl"))
   val photoStore=ArchivePhotoStore(context,File(root,"photos"))
   val photo=photoStore.importSelected(Uri.parse("content://${context.packageName}.v13-photo-fixture/blue"))
   c.runtimeArchives.save(runtime.copy(tags=listOf("STEP13_격리"))) // test-entered tag, not inferred metadata
   val saver=HeartsReviewSaver(c.runtimeArchives,history,c.localIdentity.id,{port.catalog.value},{true}) // handheld test, not Car UX permission
   var showReview by mutableStateOf(true)
   val viewName="step13-view-${root.name}"
   ui.setContent {if(showReview)ReviewFinalTheme{ReviewFinalBackground {
    MobileV12Review(record,port.catalog.value,emptyList(),{}){included,done->
     UiWriteDispatch.submit({val saved=saver.save(record.key,included,ArchiveMediaDraft(listOf(photo),photo.id,"STEP13 격리 메모"))
      main{done(saved);if(saved.persistedReference!=null)showReview=false}},{main{done(CarV10SaveResult(null,"test save failed"))}})
    }
   }} else GleoScreen(port,history,c.runtimeArchives,c.localIdentity.id,{null},{},{},{},viewName,
    ux=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION"),mobileLayout=true,photoStore=photoStore)}
   ui.onNodeWithTag("mobile_review_${track.id}").performTouchInput{swipe(Offset(width*.5f,height*.85f),Offset(width*.5f,height*.1f),450)}
   ui.onNodeWithTag("mobile_review_${track.id}").assertDoesNotExist()
   assertEquals(1,c.runtimeArchives.load().size) // Review exclusion never erases a runtime pin.
   ui.onNodeWithTag("mobile_review_undo").assertIsDisplayed().performClick()
   ui.onNodeWithTag("mobile_review_${track.id}").assertExists()
   ui.onNodeWithTag("mobile_review_remember").assertIsDisplayed().performClick()
   ui.waitUntil(15000){!showReview}
   val saved=LocalArchiveRepository(File(root,"journal/archive")).load().single()
   assertEquals(runtime.id,saved.id);assertEquals(runtime.runtimeHeart,saved.runtimeHeart)
   assertEquals(listOf(track.id),saved.heartsReview!!.includedMusicIds);assertTrue(pb in saved.heartsReview.playbackSessionIds)
   assertEquals("STEP13 격리 메모",saved.note);assertEquals(listOf("STEP13_격리"),saved.tags)
   assertNotNull(ArchiveCover.decode(context,ArchiveCover.selected(saved)!!));assertNull(saved.place)
   ui.onNodeWithTag("nav_3").performClick()
   ui.onNodeWithTag("v12_pin_grid").performClick()
   ui.onNodeWithTag("hearts_search").performTextInput(track.title)
   ui.onAllNodesWithContentDescription("Hearts 상세 열기").assertCountEquals(1)
   ui.onNodeWithContentDescription("Hearts 상세 열기").performClick()
   ui.onNode(hasText("STEP13 격리 메모") and hasAnyAncestor(isDialog())).assertExists()
   ui.onNodeWithText("#STEP13_격리").assertExists()
   // Receipt contract exercise with this real playback's metadata on the SAME phone, not device sync.
   val bundle=ReviewSyncBundle.project(record,c.localIdentity.id,c.reviewSync.identity.deviceId(),c.runId,port.catalog.value,
    revision=2,recommendations=mapOf(pb to recommendation.id),hearts=listOf(saved))
   val receiverId=SyncIdentityRepository(File(root,"local-simulation/identity"),"STEP13_ISOLATED_RECEIVER")
   receiverId.authorizePeer(bundle.localUserId,bundle.sourceDeviceId)
   val inbox=DurableReviewInbox(File(root,"local-simulation/inbox"),"STEP13_ISOLATED_RECEIVER",receiverId)
   assertEquals(SyncAcceptance.APPLIED,inbox.accept(bundle.encode()))
   assertEquals(SyncAcceptance.DUPLICATE,inbox.accept(bundle.encode()))
   assertEquals(recommendation.id,inbox.bundles().single().occurrences.single{it.playbackSessionId==pb}.recommendationId)
   inbox.setExcluded(bundle.bundleId,setOf(track.id))
   val receivedArchives=LocalArchiveRepository(File(root,"local-simulation/archive"))
   assertTrue(runCatching{inbox.saveHearts(bundle.bundleId,receivedArchives,Instant.now().toString())}.isFailure)
   assertTrue(receivedArchives.load().isEmpty())
   inbox.setExcluded(bundle.bundleId,emptySet())
   val received=inbox.saveHearts(bundle.bundleId,receivedArchives,Instant.now().toString()).single()
   assertEquals(track.id,received.track!!.id);assertTrue(pb in received.heartsReview!!.playbackSessionIds)
   inbox.saveHearts(bundle.bundleId,receivedArchives,Instant.now().toString());assertEquals(1,receivedArchives.load().size)
   assertEquals(SyncAcceptance.APPLIED,inbox.accept(bundle.copy(revision=3).encode()))
   assertEquals(SyncAcceptance.STALE,inbox.accept(bundle.encode()))
   assertEquals("STEP13 격리 메모",inbox.bundles().single().hearts.single().note)
   // Current v1 bridge retains original note in bundle, but does NOT import it or photo/capture into Pin.
   assertEquals("",received.note);assertTrue(received.attachments.isEmpty());assertNull(received.runtimeHeart)
   assertEquals(setOf(received.id),inbox.savedArchiveIds(bundle.bundleId).toSet())
   val date=dateOf(record.startedAt,ZoneId.systemDefault())!!
   assertEquals(saved.id,ArchiveProjection.onDate(HistorySnapshot(listOf(record)),listOf(saved),date).second.single().id)
   assertEquals(feedbackBefore,feedbackCount());assertEquals(ledgerHash,sha(File(root,"journal/ai-pipeline/v1/ledger.jsonl")))
   journal.forEach{(n,h)->assertEquals(h,sha(File(root,"journal/events/$n")))}
   val proof=JSONObject().put("source","GALAXY_REAL_MEDIA3_AND_UI_ISOLATED_STORES_NO_CAR_TRANSPORT")
    .put("root",root.absolutePath).put("pid",android.os.Process.myPid()).put("archiveId",saved.id).put("musicId",track.id)
    .put("driveSessionId",drive).put("appRunId",c.runId).put("recommendationId",recommendation.id).put("playbackSessionId",pb)
    .put("recommendationContextSnapshotId",bound.ids.contextSnapshotId).put("pinContextSnapshotId",captured.contextSnapshotId)
    .put("note",saved.note).put("photoSha256",sha(File(Uri.parse(photo.localReference).path!!)))
    .put("archiveSha256",sha(File(root,"journal/archive/pins.json"))).put("runtimeReviewSameId",true)
    .put("sameDeviceInboxActualMetadata",true).put("receivedHeartsNoteImported",false).put("photosInBundle",false)
    .put("journalAndLedgerUnchangedByArchiving",true).put("locationObserved",false)
   File(root,"proof.json").writeText(proof.toString(2));File(context.cacheDir,"step13-archive-pointer.txt").writeText(root.name)
   i.uiAutomation.takeScreenshot()?.let{b->File(root,"hearts.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
  } finally {
   main{port.close();browser.stop();browser.clearMediaItems();c.stopCollection();browser.release()}
   context.stopService(Intent(context,RegistryPlaybackTestService::class.java))
  }
 }
}

/** Separate instrumentation invocation; no production user files or photo picker access. */
class Step13ArchiveRestartTest {
 @Test fun archivePhotoAndCausalIdsSurviveNewApplicationProcess() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name=File(context.cacheDir,"step13-archive-pointer.txt").readText().trim()
  require(name.matches(Regex("step13-service-[a-f0-9-]{36}")))
  val root=File(context.cacheDir,name);val proof=JSONObject(File(root,"proof.json").readText())
  assertNotEquals(proof.getInt("pid"),android.os.Process.myPid())
  val repo=LocalArchiveRepository(File(root,"journal/archive"));val pin=repo.load().single()
  assertEquals(proof.getString("archiveId"),pin.id);assertEquals(proof.getString("musicId"),pin.track!!.id)
  assertEquals(proof.getString("note"),pin.note);assertNull(pin.place)
  val capture=pin.runtimeHeart!!.captures.single()
  assertEquals(proof.getString("playbackSessionId"),capture.playbackSessionId)
  assertEquals(proof.getString("recommendationId"),capture.recommendationId)
  assertNotNull(ArchiveCover.decode(context,ArchiveCover.selected(pin)!!))
  val before=File(root,"journal/archive/pins.json").readBytes()
  val history=JsonlHistoryRepository(File(root,"journal/events"),pin.runtimeHeart.localUserId)
  val record=history.refresh().drives.single{it.key==pin.sourceDriveKey}
  repo.saveReview(record,listOf(pin.track),listOf(pin.track.id),pin.runtimeHeart.localUserId,ZoneId.systemDefault().id,Instant.now().toString())
  assertEquals(1,repo.load().size);assertEquals(pin,repo.load().single());assertArrayEquals(before,File(root,"journal/archive/pins.json").readBytes())
  repo.delete(pin.id);assertTrue(repo.load().isEmpty());repo.restore(pin.id);assertEquals(pin,repo.load().single())
  proof.put("newProcessRecovered",true).put("duplicateReviewUnchanged",true).put("deleteRestoreRecovered",true)
  File(root,"proof.json").writeText(proof.toString(2))
 }
}
