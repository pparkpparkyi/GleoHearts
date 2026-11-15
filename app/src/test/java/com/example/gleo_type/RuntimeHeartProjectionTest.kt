package com.example.gleo_type

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeHeartProjectionTest {
 @Test fun demoAlternativePhraseIsRecognizedWithoutBecomingDislike(){
  assertEquals(com.example.gleo_type.recommendation.MusicRequestIntent.ALTERNATIVE,
   com.example.gleo_type.recommendation.MusicRequestParser.parse("다른 노래 추천해 줘").intent)
  assertTrue(com.example.gleo_type.context.LocalHeartsCommand.matches("이 노래 기억해 줘"))
 }
 private fun request(pb:String="playback",event:String="event",user:String="local")=RuntimeHeartRequest(user,"run","drive","device",
  Track("music","Title","Artist"),RuntimeHeartCapture(event,pb,"2026-11-14T00:00:00Z",100,2500,10000,
   null,null,null,null,"isolated_test",null),null)
 @Test fun repeatSamePlaybackIsIdempotentAndLocationRemainsUnknown(){
  val first=RuntimeHeartProjection.merge(null,request())
  val next=RuntimeHeartProjection.merge(first,request(event="other"))
  assertEquals(first,next);assertNull(next.place);assertEquals(1,next.runtimeHeart!!.captures.size)
 }
 @Test fun replayAddsMomentButPreservesRecordAndUserEdits(){
  val first=RuntimeHeartProjection.merge(null,request()).copy(note="memo",tags=listOf("tag"))
  val next=RuntimeHeartProjection.merge(first,request("replay","new"))
  assertEquals(first.id,next.id);assertEquals("memo",next.note);assertEquals(first.tags,next.tags)
  assertEquals(2,next.runtimeHeart!!.captures.size)
 }
 @Test(expected=IllegalArgumentException::class) fun differentUserCannotMerge(){
  RuntimeHeartProjection.merge(RuntimeHeartProjection.merge(null,request()),request(user="other"))
 }
 @Test fun unknownStaleAndFullDrivingRestrictionsFailClosed(){
  assertFalse(RuntimeHeartProjection.carTouchAllowed(DrivingUx(),100))
  val known=DrivingUx(true,false,0,"CarUxRestrictionsManager",100)
  assertTrue(RuntimeHeartProjection.carTouchAllowed(known,100))
  assertFalse(RuntimeHeartProjection.carTouchAllowed(known,6101))
  assertFalse(RuntimeHeartProjection.carTouchAllowed(known.copy(activeRestrictions=511),100))
  assertFalse(RuntimeHeartProjection.carTouchAllowed(known,99))
 }
}
