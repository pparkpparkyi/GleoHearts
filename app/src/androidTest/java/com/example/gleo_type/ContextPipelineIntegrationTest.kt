package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.context.*
import com.example.gleo_type.music.RegistryPlaybackTestService
import com.example.gleo_type.recommendation.RecommendationModelAdapter
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

class ContextPipelineIntegrationTest {
    @get:Rule val activity=createAndroidComposeRule<CarV10TestActivity>()
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private val root get()=File(context.cacheDir,"audio-registry-approved-samples")
    private fun main(block:()->Unit)=i.runOnMainSync(block)
    private fun <T> request(block:()->ListenableFuture<T>):T {lateinit var f:ListenableFuture<T>;main {f=block()};return f.get(45,TimeUnit.SECONDS)}
    private fun await(label:String,condition:()->Boolean) {val until=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<until){if(condition())return;Thread.sleep(50)};fail(label)}
    private fun connect():MediaBrowser {
        RegistryPlaybackTestService.testRoot=root
        return request {MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,RegistryPlaybackTestService::class.java))).buildAsync()}
    }
    private fun close(b:MediaBrowser) {
        val count=RegistryPlaybackTestService.destructions
        main {b.stop();b.clearMediaItems()};await("queue cleared"){var empty=false;main{empty=b.mediaItemCount==0};empty}
        main {b.release()};context.stopService(Intent(context,RegistryPlaybackTestService::class.java))
        await("service destroyed"){RegistryPlaybackTestService.destructions>count}
    }
    private fun ledgerFile()=File(root,"journal/ai-pipeline/v1/ledger.jsonl")
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun rows(run:String)=File(root,"journal/events").listFiles().orEmpty().filter{it.extension=="jsonl"}
        .flatMap{it.readLines().filter(String::isNotBlank).map(::JSONObject)}.filter{it.optString("runId")==run}

    @Test fun simulatedCommandSnapshotsSpeedRecommendsPlaysAndTargetsNeutralAndExplicitFeedback() {
        val b=connect()
        try {
            val library=request {b.getLibraryRoot(null)}.value!!
            val catalog=request {b.getChildren(library.mediaId,0,100,null)}.value!!.map {
                Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())}
            assertEquals(14,catalog.size)
            val c=RegistryPlaybackTestService.activeContainer!!
            main {c.startCollection();b.volume=0f}
            val drive=c.driveSessions.currentDriveSessionId()!!
            val owner="context-test-"+UUID.randomUUID()
            c.drivingContext.attach(owner);c.drivingContext.connection(owner,CarConnectionSnapshot(CarConnectionState.CONNECTED,9001))
            repeat(9){Thread.sleep(1000);c.speedSamples.accept(0f,SystemClock.elapsedRealtime(),null)}
            val model=RecommendationModelAdapter(File(root,"model-fixture"),c.localIdentity.id,contextRunId={c.runId})
            assertTrue(model.load())
            if(!model.availability(catalog.map{it.id}.toSet()).hasPreference)model.initialize(setOf("pop"),setOf("happy"))
            val command=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.RECOMMEND_AND_PLAY,
                "신나는 음악 틀어 줘",Instant.now().toString(),SystemClock.elapsedRealtime(),mapOf("weather" to "rain"))
            val baseline=model.recommend(catalog.filter{it.id.startsWith("jamendo_api_")},MusicScope.THREE,
                emptySet(),emptySet(),0,null,drive,command.musicRequest)
            val plan=c.contextPipeline.recommend(command,catalog,model)
            assertEquals(RecommendationOrigin.AI_MODEL,plan.run.origin)
            assertEquals(ContextFreshness.FRESH,plan.snapshot.driving.getValue("speedState").freshness)
            assertTrue(plan.run.drivingDecision!!.applied)
            assertTrue(plan.run.drivingDecision!!.scores.any{it.contextCorrection>0})
            plan.run.drivingDecision!!.scores.forEach {score->
                assertEquals(baseline.scores.getValue(score.musicId)+score.contextCorrection,plan.run.scores.getValue(score.musicId),1e-12)
            }
            for(field in listOf(plan.snapshot.currentLocation.getValue("position"),plan.snapshot.destination.getValue("navigation"),
                plan.snapshot.road.getValue("congestionSegments"),plan.snapshot.timeWeather.getValue("weather")))assertEquals(ContextFreshness.MISSING,field.freshness)
            assertEquals("USER_STATEMENT",plan.snapshot.voice.getValue("userClaims").origin)
            val id=plan.run.musicIds.first();val ticket=c.contextPipeline.preparePlayback(plan.recommendationId,id)
            val wrong=plan.run.musicIds.first{it!=id}
            assertFalse(c.contextPipeline.validateTicket(ticket.playbackRequestId,wrong))
            main {b.setMediaItem(CausalMediaItem.request(ticket));b.prepare();b.play()}
            await("canonical track actually playing"){var ok=false;main {ok=b.isPlaying && b.currentPosition>=500 && b.currentMediaItem?.mediaId==id};ok}
            c.causalWritesBarrier().get(10,TimeUnit.SECONDS)
            val playback=c.playbackSessions.currentId()!!
            val bound=c.contextPipeline.ledger.records().single{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==playback}
            assertEquals(plan.recommendationId,bound.ids.recommendationId);assertEquals(plan.snapshot.contextSnapshotId,bound.ids.contextSnapshotId)
            assertEquals(command.voiceRequestId,bound.ids.voiceRequestId);assertEquals(id,bound.ids.musicId)
            assertFalse(c.contextPipeline.validateTicket(ticket.playbackRequestId,id)) // one occurrence only
            val pause=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.PAUSE,"일시정지",Instant.now().toString(),SystemClock.elapsedRealtime())
            main{b.pause()};await("pause observed"){var paused=false;main{paused=!b.isPlaying};paused}
            c.contextPipeline.commandExecuted(pause,playback,id,"SUCCESS")
            val pauseId=c.contextPipeline.feedback(playback,id,FeedbackKind.PAUSE,pause.voiceRequestId)
            val like=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.LIKE,"이 노래 좋아",Instant.now().toString(),SystemClock.elapsedRealtime())
            c.contextPipeline.commandExecuted(like,playback,id,"SUCCESS")
            val feedback=c.contextPipeline.feedback(playback,id,FeedbackKind.LIKE,like.voiceRequestId)
            val before=hash(ledgerFile())
            assertTrue(runCatching{c.contextPipeline.feedback(playback,wrong,FeedbackKind.LIKE,like.voiceRequestId)}.isFailure)
            assertTrue(runCatching{c.contextPipeline.feedback(playback,id,FeedbackKind.LIKE,like.voiceRequestId)}.isFailure)
            assertEquals(before,hash(ledgerFile()))
            runBlocking {withTimeout(10000){c.eventPublisher.awaitSubmittedWrites()}}
            assertTrue(rows(c.runId).any {it.optString("playbackSessionId")==playback &&
                it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==id && it.optJSONObject("event")?.optString("label")=="STARTED"})
            val records=PipelineLedger(ledgerFile()).records()
            assertEquals("neutral_or_unknown",records.single{it.ids.feedbackEventId==pauseId}.payload.getString("polarity"))
            assertEquals("explicit",records.single{it.ids.feedbackEventId==feedback}.payload.getString("strength"))
            assertEquals("CONTRACT_ONLY_NOT_APPLIED",records.single{it.ids.feedbackEventId==feedback}.payload.getString("learningDisposition"))
            File(root,"context-pipeline-proof.json").writeText(JSONObject().put("passed",true).put("source","TEST_TEXT_SYNTHETIC_SPEED_REAL_GALAXY_MEDIA3")
                .put("processId",android.os.Process.myPid()).put("localUserId",c.localIdentity.id).put("appRunId",c.runId).put("driveSessionId",drive)
                .put("voiceRequestId",command.voiceRequestId).put("contextSnapshotId",plan.snapshot.contextSnapshotId)
                .put("recommendationId",plan.recommendationId).put("engineRunId",plan.run.id).put("musicId",id)
                .put("playbackRequestId",ticket.playbackRequestId).put("playbackSessionId",playback).put("feedbackEventId",feedback)
                .put("snapshot",JSONObject(plan.snapshot.toMap())).put("scores",JSONObject(plan.run.scores))
                .put("baselineScores",JSONObject(baseline.scores)).put("speedCorrectionApplied",true)
                .put("officialGleoVerified",false).put("actualVehicleSensorVerified",false).toString(2))
            main{c.stopCollection()}
        } finally {close(b)}
    }

    /** Run separately after the scenario so Android instrumentation starts a new app process. */
    @Test fun restoreLedgerAfterNewAppProcessDoesNotReuseOldLiveContext() {
        val proof=JSONObject(File(root,"context-pipeline-proof.json").readText())
        assertNotEquals(proof.getInt("processId"),android.os.Process.myPid())
        val original=hash(ledgerFile());val b=connect()
        try {
            val library=request{b.getLibraryRoot(null)}.value!!
            assertEquals(14,request{b.getChildren(library.mediaId,0,100,null)}.value!!.size)
            val c=RegistryPlaybackTestService.activeContainer!!
            assertNotEquals(proof.getString("appRunId"),c.runId)
            val restored=PipelineLedger(ledgerFile()).records()
            val feedback=restored.single{it.ids.feedbackEventId==proof.getString("feedbackEventId")}
            assertEquals(proof.getString("musicId"),feedback.ids.musicId)
            assertEquals(proof.getString("recommendationId"),feedback.ids.recommendationId)
            assertFalse(c.contextPipeline.validateTicket(proof.getString("playbackRequestId"),proof.getString("musicId")))
            assertEquals("unknown",c.contextSnapshots.capture(null).drivingInput.quality)
            assertEquals(original,hash(ledgerFile()))
            File(root,"context-pipeline-restart.json").writeText(JSONObject().put("passed",true)
                .put("previousProcessId",proof.getInt("processId")).put("newProcessId",android.os.Process.myPid())
                .put("ledgerRestored",true).put("oldContextNotReused",true).put("ledgerUnchanged",true).toString(2))
        } finally {close(b)}
    }

    @Test fun ledgerRejectsWrongIdsAndTruncatedTailWithoutOverwritingEvidence() {
        val file=File(context.cacheDir,"context-negative-${UUID.randomUUID()}/ledger.jsonl")
        val ledger=PipelineLedger(file);val ids=CausalIds("synthetic-user","synthetic-run","synthetic-drive","voice")
        ledger.append(LedgerKind.VOICE_REQUEST,ids,mapOf("source" to "SYNTHETIC_CONTRACT_TEST"),"voice")
        ledger.append(LedgerKind.CONTEXT_SNAPSHOT,ids.copy(contextSnapshotId="snapshot"),emptyMap(),"snapshot")
        val before=hash(file)
        assertTrue(runCatching{ledger.append(LedgerKind.RECOMMENDATION_REQUEST,ids.copy(localUserId="other",contextSnapshotId="snapshot",recommendationId="r"),emptyMap())}.isFailure)
        assertTrue(runCatching{ledger.append(LedgerKind.RECOMMENDATION_REQUEST,ids.copy(contextSnapshotId="missing",recommendationId="r"),emptyMap())}.isFailure)
        assertEquals(before,hash(file))
        file.appendText("{incomplete")
        val truncated=hash(file)
        assertTrue(runCatching{PipelineLedger(file).records()}.isFailure)
        assertTrue(runCatching{ledger.append(LedgerKind.VOICE_REQUEST,ids.copy(voiceRequestId="other"),emptyMap())}.isFailure)
        assertEquals(truncated,hash(file))
    }
}
