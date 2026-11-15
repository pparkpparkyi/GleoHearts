package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Step8cLearningRestartTest {
 @Test fun ordinaryUiCaseAndPendingPreferenceRestoreWithoutDuplicateLearning() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val proof=JSONObject(File(c.cacheDir,"step8c-learning-proof.json").readText())
  assertNotEquals(proof.getInt("pid"),android.os.Process.myPid())
  val root=File(proof.getString("fixtureRoot")).canonicalFile;require(root.toPath().startsWith(c.cacheDir.canonicalFile.toPath()))
  val user=proof.getString("user");val cases=ExperienceCaseRepository.forUser(root,user)
  val before=cases.cases();assertEquals(1,before.size);assertEquals("UI_EXPLICIT_LEDGER_FEEDBACK",before.single().origin)
  val model=RecommendationModelAdapter(root,user);assertTrue(model.load());assertTrue(model.availability(setOf(proof.getString("musicId"))).hasPreference)
  val ledger=PipelineLedger(File(c.cacheDir,"audio-registry-approved-samples/journal/ai-pipeline/v1/ledger.jsonl"))
  val feedback=ledger.records().single{it.ids.playbackSessionId==proof.getString("playback")&&it.kind==LedgerKind.LISTENING_FEEDBACK}
  assertTrue(model.learnUiFeedback(ledger,feedback.ids.feedbackEventId!!));assertEquals(before,cases.cases())
 }
}
