package com.example.gleo_type

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class V13ArchiveContractTest {
 private val photo=ArchiveAttachment("photo","photo","file:///isolated/photo.jpg","selected","image/jpeg")
 @Test fun coverMustReferenceSelectedPhotoAndEmptyPhotoSaveRemainsValid(){
  assertTrue(runCatching{ArchiveMediaDraft(listOf(photo),"missing","")}.isFailure)
  assertTrue(runCatching{ArchiveMediaDraft(listOf(photo,photo),photo.id,"")}.isFailure)
  assertTrue(runCatching{ArchiveMediaDraft(listOf(photo.copy(kind="document")),photo.id,"")}.isFailure)
  assertEquals(emptyList<ArchiveAttachment>(),ArchiveMediaDraft(emptyList(),null,"memo").attachments)
 }
 @Test fun planRejectsDuplicateIdsAndInvalidTimezone(){
  val at="2026-11-14T00:00:00Z"
  assertTrue(runCatching{MusicPlan("plan","2026-11-16","Asia/Seoul","Plan",listOf("a","a"),at)}.isFailure)
  assertTrue(runCatching{MusicPlan("plan","2026-11-16","not/a/zone","Plan",listOf("a"),at)}.isFailure)
  assertEquals(listOf("a","b"),MusicPlan("plan","2026-11-16","Asia/Seoul","Plan",listOf("a","b"),at).musicIds)
 }
 @Test fun runtimeSavePreservesReviewPhotoCoverAndNotes(){
  val request=RuntimeHeartRequest("synthetic","run","drive","device",Track("a","A","Artist"),
   RuntimeHeartCapture("event","playback","2026-11-14T00:00:00Z",100,50,10000,null,null,null,null,"isolated",null),null)
  val original=RuntimeHeartProjection.merge(null,request).copy(attachments=listOf(photo),coverAttachmentId=photo.id,note="user note")
  val next=RuntimeHeartProjection.merge(original,request.copy(capture=request.capture.copy(pinEventId="another")))
  assertEquals(original,next);assertEquals(photo.id,next.coverAttachmentId)
 }
}
