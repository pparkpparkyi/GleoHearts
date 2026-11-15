package com.example.gleo_type

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

/** Synthetic image provider + isolated cache. No user media scanning or production archive writes. */
class V13PhotoArchiveTest {
 @get:Rule val ui=createAndroidComposeRule<CarV10TestActivity>()
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private fun directory()=File(context.cacheDir,"v13-photo-${UUID.randomUUID()}").apply{mkdirs()}
 private fun uri(name:String)=Uri.parse("content://${context.packageName}.v13-photo-fixture/$name")
 private fun drive()=DriveRecord("test/drive","drive","2026-11-14T00:00:00Z","2026-11-14T00:10:00Z",listOf("a","b"),1000.0,true,null,true)
 private val tracks=listOf(Track("a","테스트 음악 A","테스트"),Track("b","테스트 음악 B","테스트"))
 @Test fun selectedPhotoIsLocalNormalizedAndMissingLocationStaysUnknown(){
  val root=directory();val photo=ArchivePhotoStore(context,File(root,"photos")).importSelected(uri("red"))
  val file=File(Uri.parse(photo.localReference).path!!)
  assertTrue(file.isFile);assertEquals("image/jpeg",photo.mimeType)
  assertTrue(file.readBytes().take(2)==listOf(0xFF.toByte(),0xD8.toByte()))
  assertNotNull(ArchiveCover.decode(context,photo))
  assertTrue(runCatching{ArchivePhotoStore(context,File(root,"photos")).importSelected(Uri.parse("https://not-requested.example/image.jpg"))}.isFailure)
 }
 @Test fun corruptImageNeverChangesExistingArchive(){
  val root=directory();val repo=LocalArchiveRepository(File(root,"archive"));val pin=Pin("legacy","2026-11-14T00:00:00Z",null,null,null,emptyList(),"old memo")
  repo.save(pin);val before=File(root,"archive/pins.json").readBytes()
  assertTrue(runCatching{ArchivePhotoStore(context,File(root,"photos")).importSelected(uri("corrupt"))}.isFailure)
  assertArrayEquals(before,File(root,"archive/pins.json").readBytes());assertEquals(pin,repo.load().single())
 }
 @Test fun reviewPhotosCoverNotesRoundTripAndDeduplicate(){
  val root=directory();val store=ArchivePhotoStore(context,File(root,"photos"));val a=store.importSelected(uri("red"));val b=store.importSelected(uri("blue"))
  val repo=LocalArchiveRepository(File(root,"archive"));val media=ArchiveMediaDraft(listOf(a,b),b.id,"사진 메모")
  val one=repo.saveReviewWithMedia(drive(),tracks,listOf("a"),"synthetic","Asia/Seoul","2026-11-14T00:11:00Z",media).single()
  repo.saveReviewWithMedia(drive(),tracks,listOf("a"),"synthetic","Asia/Seoul","2026-11-14T00:12:00Z",media)
  val reloaded=LocalArchiveRepository(File(root,"archive")).load().single()
  assertEquals(one.id,reloaded.id);assertEquals(2,reloaded.attachments.size);assertEquals(b.id,ArchiveCover.selected(reloaded)!!.id)
  assertEquals("사진 메모",reloaded.note);assertNull(reloaded.place)
  assertEquals(reloaded,ArchiveProjection.onDate(HistorySnapshot(listOf(drive())),listOf(reloaded),LocalDate.parse("2026-11-14"),ZoneId.of("Asia/Seoul")).second.single())
  repo.delete(one.id);assertTrue(repo.load().isEmpty());repo.restore(one.id);assertEquals(reloaded,repo.load().single())
 }
 @Test fun pickerResultContractIsSimulatedButImportCoverAndSaveAreReal(){
  val root=directory();val repo=LocalArchiveRepository(File(root,"archive"));val store=ArchivePhotoStore(context,File(root,"photos"))
  val data=Intent().apply{clipData=ClipData.newRawUri("synthetic",uri("red")).apply{addItem(ClipData.Item(uri("blue")))}}
  val monitor=instrumentation.addMonitor(IntentFilter("android.provider.action.PICK_IMAGES").apply{addDataType("image/*");addAction(Intent.ACTION_OPEN_DOCUMENT);addCategory(Intent.CATEGORY_OPENABLE)},ActivityResult(Activity.RESULT_OK,data),true)
  try {
   ui.setContent {MaterialTheme(colorScheme=V13Design.colors()) {ArchivePhotoEditor("synthetic-${root.name}",store,onCancel={},onSaved={},onSave={media,done->
    val saved=repo.saveReviewWithMedia(drive(),tracks,listOf("a"),"synthetic","UTC","2026-11-14T00:11:00Z",media)
    done(CarV10SaveResult(saved.single().id,"saved"))})}}
   ui.onNodeWithTag("v13_photo_pick").performClick()
   ui.waitUntil(15000){runCatching{ui.onAllNodesWithTag("v13_photo_cover_1").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
   assertTrue(monitor.hits>0)
   ui.onNodeWithTag("v13_photo_cover_1").performClick()
   ui.onNodeWithTag("v13_photo_note").performTextInput("격리 사진 메모")
   ui.onNodeWithTag("v13_photo_save").performClick()
   ui.waitUntil(10000){repo.load().size==1}
   val pin=repo.load().single();assertEquals(2,pin.attachments.size);assertEquals(pin.attachments[1].id,pin.coverAttachmentId)
   assertEquals("격리 사진 메모",pin.note)
   androidx.test.espresso.Espresso.closeSoftKeyboard();ui.onNodeWithText("오늘의 사진도 기억할까요?").performScrollTo()
   ui.onRoot().captureToImage().asAndroidBitmap().let{bitmap->File(context.cacheDir,"v13-photo-editor.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
  } finally {instrumentation.removeMonitor(monitor)}
 }
 @Test fun cancelledPickerKeepsPhotoFreeSavePossible(){
  val root=directory();var result:ArchiveMediaDraft?=null
  val monitor=instrumentation.addMonitor(IntentFilter("android.provider.action.PICK_IMAGES").apply{addDataType("image/*");addAction(Intent.ACTION_OPEN_DOCUMENT);addCategory(Intent.CATEGORY_OPENABLE)},ActivityResult(Activity.RESULT_CANCELED,null),true)
  try {
   ui.setContent {MaterialTheme(colorScheme=V13Design.colors()){ArchivePhotoEditor("cancel-${root.name}",ArchivePhotoStore(context,File(root,"photos")),onCancel={},onSaved={},onSave={media,done->result=media;done(CarV10SaveResult("synthetic","saved"))})}}
   ui.onNodeWithTag("v13_photo_pick").performClick();ui.waitForIdle()
   ui.onNodeWithTag("v13_photo_save").assertIsEnabled().performClick()
   assertTrue(result!!.attachments.isEmpty());assertNull(result!!.coverAttachmentId)
  }finally{instrumentation.removeMonitor(monitor)}
 }
 @Test fun actualSystemPickerLaunchAndCancelDoesNotSelectPersonalPhotos(){
  val root=directory()
  ui.setContent {MaterialTheme(colorScheme=V13Design.colors()){ArchivePhotoEditor("real-picker-${root.name}",ArchivePhotoStore(context,File(root,"photos")),onCancel={},onSaved={},onSave={_,_->fail("Cancel must not save")})}}
  ui.onNodeWithTag("v13_photo_pick").performClick()
  // Observe only the active root package; never inspect thumbnails/gallery descendants.
  val expected=ActivityResultContracts.PickMultipleVisualMedia(5).createIntent(context,PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)).resolveActivity(context.packageManager)?.packageName
  assertNotNull(expected)
  val deadline=android.os.SystemClock.elapsedRealtime()+10000
  // Android 17 delegates the media-provider entry to the separate system Photo Picker UI.
  fun isPicker(pkg:String?)=pkg==expected || pkg in setOf("com.google.android.photopicker","com.android.photopicker")
  while(!isPicker(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()) && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
  assertTrue("Expected the resolved system picker or its delegated UI",isPicker(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()))
  // Picker is a different process; Espresso's in-app root cannot drive its Back action.
  assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
  ui.waitUntil(15000){runCatching{ui.onAllNodesWithTag("v13_photo_editor").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
  ui.onNodeWithTag("v13_photo_save").assertIsEnabled()
  assertFalse(File(root,"photos").exists())
 }
 @Test fun photoDraftSavedStateRestoresWithoutGalleryPermission(){
  val root=directory();val store=ArchivePhotoStore(context,File(root,"photos"));val p=store.importSelected(uri("blue"))
  val handle=SavedStateHandle();val vm=ArchivePhotoViewModel(handle,store)
  vm.initialize(Pin("saved","2026-11-14T00:00:00Z",null,null,null,emptyList(),"restore",attachments=listOf(p),coverAttachmentId=p.id))
  val restored=ArchivePhotoViewModel(SavedStateHandle(mapOf("draft" to handle.get<String>("draft"))),store)
  assertEquals(vm.draft(),restored.draft());assertNotNull(ArchiveCover.decode(context,restored.draft().attachments.single()))
 }
 @Test fun selectedPhotoCoverAndMemoSurviveActualActivityRecreation(){
  val root=directory();val repo=LocalArchiveRepository(File(root,"archive"));val store=ArchivePhotoStore(context,File(root,"photos"))
  val image=store.importSelected(uri("blue"))
  val initial=Pin("SYNTHETIC_ROTATION","2026-11-14T00:00:00Z",null,tracks[0],null,emptyList(),"",attachments=listOf(image),coverAttachmentId=image.id)
  CarV10TestActivity.recreationContent={MaterialTheme(colorScheme=V13Design.colors()){
   ArchivePhotoEditor(root.name,store,initial,onCancel={},onSaved={},onSave={media,done->
    val p=initial.copy(attachments=media.attachments,coverAttachmentId=media.coverAttachmentId,note=media.note)
    repo.save(p);done(CarV10SaveResult(p.id,"saved"))
   })}}
  try {
   ui.setContent {CarV10TestActivity.recreationContent?.invoke()}
   ui.waitUntil(10000){ui.onAllNodesWithTag("v13_photo_cover_0").fetchSemanticsNodes().isNotEmpty()}
   ui.onNodeWithTag("v13_photo_note").performScrollTo().performTextInput("재생성 후 유지할 메모")
   androidx.test.espresso.Espresso.closeSoftKeyboard()
   ui.activityRule.scenario.recreate()
   ui.onNodeWithTag("v13_photo_note").performScrollTo().assertTextContains("재생성 후 유지할 메모")
   ui.onNodeWithTag("v13_photo_cover_0").performScrollTo().assertTextContains("✓ 사진 1")
   ui.onNodeWithTag("v13_photo_save").performClick()
   ui.waitUntil(10000){repo.load().size==1}
   assertEquals(image.id,repo.load().single().coverAttachmentId)
   assertEquals("재생성 후 유지할 메모",repo.load().single().note)
  }finally{CarV10TestActivity.recreationContent=null}
 }
 @Test fun futureMusicPlanPersistsSeparatelyWithTimezoneAndNoListeningRows(){
  val root=directory();val now={Instant.parse("2026-11-14T23:30:00Z")}
  val repo=LocalMusicPlanRepository(File(root,"plans.json"),"synthetic",ZoneId.of("Asia/Seoul"),now)
  val date=LocalDate.parse("2026-11-16");val a=repo.save(date,"다음 드라이브",listOf("a"));val b=repo.save(date,"선택 목록",listOf("a","b"))
  assertEquals(a.id,b.id);assertEquals(listOf(b),LocalMusicPlanRepository(File(root,"plans.json"),"synthetic").load())
  assertTrue(runCatching{repo.save(LocalDate.parse("2026-11-15"),"today",listOf("a"))}.isFailure)
  assertFalse(File(root,"events").exists());assertTrue(runCatching{LocalMusicPlanRepository(File(root,"plans.json"),"other-user").load()}.isFailure)
 }
}
