package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class Step9PersistenceTest {
 @Test fun previousProcessRuntimeHeartsAndSessionEndArePersisted(){
  val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
  val name=File(cache,"step9-demo-root.txt").readText();require(name.matches(Regex("step9-demo-[a-f0-9-]{36}")))
  val root=File(cache,"$name/journal")
  val pins=LocalArchiveRepository(File(root,"archive")).load()
  assertEquals(2,pins.size);assertTrue(pins.all{it.runtimeHeart?.captures?.size==1})
  val rows=File(root,"events").listFiles().orEmpty().filter{it.extension=="jsonl"}.flatMap{it.readLines()}
   .filter{it.isNotBlank()}.map{org.json.JSONObject(it)}
  assertEquals(1,rows.count{it.optString("kind")=="session_stopped"})
  assertEquals(1,rows.count{it.optString("kind")=="session_started"})
  assertEquals(0,rows.count{it.optJSONObject("event")?.optString("label") in setOf("LIKED","DISLIKED")})
 }
 @Test fun runtimeArchiveRoundTripReviewExclusionAndRestore(){
  val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"runtime-repo-${UUID.randomUUID()}")
  val track=Track("song","한글 음악","테스트")
  val request=RuntimeHeartRequest("synthetic","run","drive","test_device",track,
   RuntimeHeartCapture("event","playback","2026-11-14T00:00:01Z",100,50,10000,null,null,null,null,"isolated_test",null),null)
  val saved=LocalArchiveRepository(root).saveRuntime(request)
  val repo=LocalArchiveRepository(root);assertEquals(saved,repo.load().single())
  repo.save(saved.copy(note="메모",tags=listOf("태그")))
  val drive=DriveRecord("run/drive","drive","2026-11-14T00:00:00Z","2026-11-14T00:00:10Z",listOf("song","other"),100.0,true,null,true)
  repo.saveReview(drive,listOf(track,Track("other","Other","Artist")),listOf("other"),"synthetic","UTC","2026-11-14T00:00:11Z")
  assertNotNull(repo.load().single{it.id==saved.id}.runtimeHeart)
  repo.delete(saved.id);assertTrue(repo.deleted().any{it.id==saved.id})
  repo.restore(saved.id)
  assertEquals("메모",LocalArchiveRepository(root).load().single{it.id==saved.id}.note)
 }
}
