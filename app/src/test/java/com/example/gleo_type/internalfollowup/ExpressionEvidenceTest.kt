package com.example.gleo_type.internalfollowup

import com.example.gleo_type.context.*
import com.example.gleo_type.internalai.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExpressionEvidenceTest {
    private data class Rig(val ledger:PipelineLedger,val case:ExperienceCase,val history:RecommendationRun)
    private fun rig(kind:String="LIKE"):Rig {
        val f=InternalFixture;val model=f.model;val music=model.tracks[0].musicId;val c=f.case("evidence",music)
        val ledger=PipelineLedger(File(Files.createTempDirectory("expression-ledger").toFile(),"ledger.jsonl"))
        val snapshot=f.snapshot();val root=CausalIds("user","run","drive","root")
        ledger.append(LedgerKind.VOICE_REQUEST,root,mapOf("source" to "ANDROID_UI","environment" to "isolated_test"))
        val ids=root.copy(contextSnapshotId="snapshot",recommendationId="recommendation")
        ledger.append(LedgerKind.CONTEXT_SNAPSHOT,ids.copy(recommendationId=null),snapshot.toMap())
        ledger.append(LedgerKind.RECOMMENDATION_REQUEST,ids,emptyMap())
        val evidence=JSONObject().put("version",1).put("snapshot",JSONObject(snapshot.toMap()))
            .put("decision",JSONObject().put("observedFeatures",JSONObject(c.features.facets)))
            .put("modelToMedia",JSONObject().put(music,music))
        val history=RecommendationRun("history","2026-11-13T08:00:00Z",null,RecommendationOrigin.AI_MODEL,"isolated",model.modelVersion,
            MusicScope.ALL,0,listOf(music),model.fingerprint,emptyList(),"drive",contextEvidence=evidence.toString())
        ledger.append(LedgerKind.RECOMMENDATION_RESULT,ids,mapOf("engineRunId" to "history","musicIds" to listOf(music),
            "frozenEvidence" to evidence,"origin" to "AI_MODEL"))
        val request=ids.copy(musicId=music,playbackRequestId="request")
        ledger.append(LedgerKind.PLAYBACK_REQUEST,request,emptyMap())
        val bound=request.copy(playbackSessionId=c.playbackSessionId)
        ledger.append(LedgerKind.PLAYBACK_BOUND,bound,emptyMap())
        ledger.append(LedgerKind.LISTENING_FEEDBACK,bound.copy(feedbackEventId=c.feedbackEventIds.single()),mapOf(
            "source" to "ANDROID_UI","kind" to kind,"explicitUiAction" to (kind=="LIKE"),"actualPlaybackVerified" to true))
        return Rig(ledger,c,history)
    }
    @Test fun exactCausalLedgerHistoryAndModelProduceTestScopedProof(){
        val r=rig();val result=LedgerExpressionEvidence.validate(listOf(r.case),r.ledger,listOf(r.history),InternalFixture.model)
        assertEquals(0,result.rejected);assertEquals(1,result.proofs.size)
        assertEquals(ExpressionEvidenceEnvironment.ISOLATED_TEST,result.proofs.single().environment)
    }
    @Test fun missingModelHistoryChangedFeaturesAndWrongUserDoNotGetProof(){
        val r=rig();val m=InternalFixture.model
        assertEquals(1,LedgerExpressionEvidence.validate(listOf(r.case),r.ledger,emptyList(),m).rejected)
        assertEquals(1,LedgerExpressionEvidence.validate(listOf(r.case),r.ledger,listOf(r.history.copy(catalogFingerprint="wrong")),m).rejected)
        assertEquals(1,LedgerExpressionEvidence.validate(listOf(r.case.copy(features=ObservedFeatures(emptyMap()))),r.ledger,listOf(r.history),m).rejected)
        assertEquals(1,LedgerExpressionEvidence.validate(listOf(r.case.copy(localUserId="other")),r.ledger,listOf(r.history),m).rejected)
    }
    @Test fun nonPreferenceActionsCannotBecomeExpressionTraining(){
        val r=rig("HEARTS_SAVE")
        assertEquals(1,LedgerExpressionEvidence.validate(listOf(r.case),r.ledger,listOf(r.history),InternalFixture.model).rejected)
    }
}
