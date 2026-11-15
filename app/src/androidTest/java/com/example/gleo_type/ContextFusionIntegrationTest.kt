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
import com.example.gleo_type.recommendation.*
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

class ContextFusionIntegrationTest {
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


    private fun actualPcFixture(): LocalClapTextEncoder {
        val j=JSONObject(i.context.assets.open("clap-text-experiment.json").bufferedReader().use{it.readText()})
        assertTrue(j.getBoolean("fixtureOnlyNotAndroidInference"))
        val results=j.getJSONArray("results")
        return LocalClapTextEncoder { text ->
            (0 until results.length()).map{results.getJSONObject(it)}.singleOrNull{it.getString("text")==text}?.let { r ->
                val v=r.getJSONArray("vector")
                TextVector(FloatArray(v.length()){v.getDouble(it).toFloat()},j.getString("modelId"),j.getString("revision"),
                    text,r.getString("language"),r.getDouble("inferenceMs"))
            }
        }
    }
    @Test fun realPcTextVectorsRankApprovedThreePlayAndRetainOnlyExplicitBoundFeedback() {
        val b=connect()
        try {
            val library=request{b.getLibraryRoot(null)}.value!!
            val catalog=request{b.getChildren(library.mediaId,0,100,null)}.value!!.map {
                Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())}
            assertEquals(14,catalog.size)
            val c=RegistryPlaybackTestService.activeContainer!!
            main {c.startCollection();b.volume=0f}
            val drive=c.driveSessions.currentDriveSessionId()!!
            val owner="fusion-test-"+UUID.randomUUID()
            c.drivingContext.attach(owner);c.drivingContext.connection(owner,CarConnectionSnapshot(CarConnectionState.CONNECTED,9002))
            repeat(9){Thread.sleep(1000);c.speedSamples.accept(0f,SystemClock.elapsedRealtime(),null)}
            val model=RecommendationModelAdapter(File(root,"model-fixture"),c.localIdentity.id,contextRunId={c.runId})
            assertTrue(model.load())
            if(!model.availability(catalog.map{it.id}.toSet()).hasPreference)model.initialize(setOf("pop"),setOf("happy"))
            val caseRoot=File(root,"fusion-isolated/"+UUID.randomUUID())
            val repo=ExperienceCaseRepository.forUser(caseRoot,c.localIdentity.id)
            assertTrue(repo.cases().isEmpty())
            val runtime=FusionRuntime(actualPcFixture(),repo)
            fun command(text:String)=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.RECOMMEND_AND_PLAY,
                text,Instant.now().toString(),SystemClock.elapsedRealtime())
            val calm=c.contextPipeline.recommend(command("잔잔하고 몽환적인 음악 틀어줘"),catalog,model,runtime)
            val energetic=c.contextPipeline.recommend(command("신나고 활기찬 음악 틀어줘"),catalog,model,runtime)
            assertEquals("REAL_LOCAL_CLAP_TEXT",calm.run.fusionDecision!!.textStatus)
            assertTrue(calm.run.scores.any{(id,v)->kotlin.math.abs(v-energetic.run.scores.getValue(id))>1e-6})
            val actual=mutableListOf<Map<String,Any?>>()
            for(id in calm.run.musicIds) {
                val ticket=c.contextPipeline.preparePlayback(calm.recommendationId,id)
                main{b.setMediaItem(CausalMediaItem.request(ticket));b.prepare();b.play()}
                await("actual Media3 playing $id"){var ok=false;main{ok=b.isPlaying && b.currentPosition>=500 && b.currentMediaItem?.mediaId==id};ok}
                c.causalWritesBarrier().get(10,TimeUnit.SECONDS)
                val pb=c.playbackSessions.currentId()!!
                val bound=c.contextPipeline.ledger.records().single{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==pb}
                assertEquals(calm.recommendationId,bound.ids.recommendationId);assertEquals(calm.snapshot.contextSnapshotId,bound.ids.contextSnapshotId)
                assertEquals(id,bound.ids.musicId)
                main{b.pause()}
                val pause=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.PAUSE,"pause",Instant.now().toString(),SystemClock.elapsedRealtime())
                c.contextPipeline.commandExecuted(pause,pb,id,"SUCCESS")
                val neutral=c.contextPipeline.feedback(pb,id,FeedbackKind.PAUSE,pause.voiceRequestId)
                assertFalse(model.retainExplicitExperience(repo,c.contextPipeline.ledger,neutral,calm.snapshot))
                val like=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.LIKE,"이 노래 좋아",Instant.now().toString(),SystemClock.elapsedRealtime())
                c.contextPipeline.commandExecuted(like,pb,id,"SUCCESS")
                val fid=c.contextPipeline.feedback(pb,id,FeedbackKind.LIKE,like.voiceRequestId)
                assertTrue(model.retainExplicitExperience(repo,c.contextPipeline.ledger,fid,calm.snapshot))
                assertFalse(model.retainExplicitExperience(repo,c.contextPipeline.ledger,fid,calm.snapshot))
                assertTrue(runCatching {model.retainExplicitExperience(repo,c.contextPipeline.ledger,fid,
                    calm.snapshot.copy(capturedElapsedMs=calm.snapshot.capturedElapsedMs+1))}.isFailure)
                actual+=mapOf("musicId" to id,"playbackRequestId" to ticket.playbackRequestId,"playbackSessionId" to pb,"feedbackEventId" to fid)
            }
            assertEquals(3,repo.cases().size)
            val reloaded=ExperienceCaseRepository.forUser(File(root,"unused"),"different-user")
            assertTrue(reloaded.cases().isEmpty())
            val after=c.contextPipeline.recommend(command("잔잔하고 몽환적인 음악 틀어줘"),catalog,model,runtime)
            assertTrue(after.run.fusionDecision!!.scores.all{it.contributions.getValue("cbr")>0})
            val noCaseRuntime=runtime.copy(cases=ExperienceCaseRepository.forUser(File(caseRoot,"empty-control"),c.localIdentity.id))
            val control=model.recommend(catalog.filter{it.id.startsWith("jamendo_api_")},MusicScope.THREE,emptySet(),emptySet(),0,null,
                drive,snapshot=after.snapshot,fusion=noCaseRuntime,utterance="잔잔하고 몽환적인 음악 틀어줘")
            after.run.scores.forEach{(id,value)->assertTrue(value>control.scores.getValue(id))}
            val current=actual.last();val currentId=current.getValue("musicId") as String
            val currentPb=current.getValue("playbackSessionId") as String
            val dislike=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.DISLIKE,"이 노래 싫어",Instant.now().toString(),SystemClock.elapsedRealtime())
            c.contextPipeline.commandExecuted(dislike,currentPb,currentId,"SUCCESS")
            val negativeId=c.contextPipeline.feedback(currentPb,currentId,FeedbackKind.DISLIKE,dislike.voiceRequestId)
            assertTrue(model.retainExplicitExperience(repo,c.contextPipeline.ledger,negativeId,calm.snapshot))
            assertEquals(3,repo.cases().size)
            assertEquals(-1.0,repo.cases().single{it.musicId==currentId}.reward,0.0)
            val revised=c.contextPipeline.recommend(command("잔잔하고 몽환적인 음악 틀어줘"),catalog,model,runtime)
            assertTrue(revised.run.fusionDecision!!.scores.single{it.musicId==currentId}.contributions.getValue("cbr")<0)
            runBlocking{withTimeout(10000){c.eventPublisher.awaitSubmittedWrites()}}
            actual.forEach { a -> assertTrue(rows(c.runId).any{it.optString("playbackSessionId")==a["playbackSessionId"] &&
                it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==a["musicId"] && it.optJSONObject("event")?.optString("label")=="STARTED"}) }
            File(root,"fusion-proof.json").writeText(JSONObject().put("passed",true).put("processId",android.os.Process.myPid())
                .put("source","PC_REAL_TEXT_VECTOR_FIXTURE_SYNTHETIC_SPEED_REAL_GALAXY_MEDIA3")
                .put("localUserId",c.localIdentity.id).put("appRunId",c.runId).put("driveSessionId",drive).put("recommendationId",calm.recommendationId)
                .put("snapshotId",calm.snapshot.contextSnapshotId).put("actualPlaybacks",org.json.JSONArray(actual))
                .put("calm",JSONObject(calm.run.fusionDecision!!.toMap())).put("energetic",JSONObject(energetic.run.fusionDecision!!.toMap()))
                .put("afterExplicitLikes",JSONObject(after.run.fusionDecision!!.toMap())).put("caseCount",repo.cases().size)
                .put("afterExplicitDislike",JSONObject(revised.run.fusionDecision!!.toMap())).put("negativeFeedbackId",negativeId)
                .put("caseRepositoryRoot",caseRoot.absolutePath).put("androidTextInference",false).put("officialGleoVerified",false).toString(2))
            main{c.stopCollection()}
        } finally {close(b)}
    }
    @Test fun casesAndCausalIdsRestoreInNewAppProcessWithoutReusingOldTicket() {
        val proof=JSONObject(File(root,"fusion-proof.json").readText())
        assertNotEquals(proof.getInt("processId"),android.os.Process.myPid())
        val caseRoot=File(proof.getString("caseRepositoryRoot"))
        val files=caseRoot.walkTopDown().filter{it.isFile}.associate{it.absolutePath to hash(it)}
        val repo=ExperienceCaseRepository.forUser(caseRoot,proof.getString("localUserId"))
        assertEquals(3,repo.cases().size);assertEquals(1,repo.cases().count{it.reward<0})
        val originalCaseFile=caseRoot.walkTopDown().single{it.isFile && it.extension=="jsonl"}
        assertTrue(runCatching{ExperienceCaseRepository(originalCaseFile,"wrong-user").cases()}.isFailure)
        val truncated=File(context.cacheDir,"isolated-truncated-case-"+UUID.randomUUID()+".jsonl")
        truncated.writeText("{\"schemaVersion\":1")
        val truncatedHash=hash(truncated)
        assertTrue(runCatching{ExperienceCaseRepository(truncated,proof.getString("localUserId")).cases()}.isFailure)
        assertEquals(truncatedHash,hash(truncated))
        assertTrue(repo.cases().all{it.contextSnapshotId==proof.getString("snapshotId") && it.recommendationId==proof.getString("recommendationId")})
        val original=hash(ledgerFile());val b=connect()
        try {
            val c=RegistryPlaybackTestService.activeContainer!!
            assertNotEquals(proof.getString("appRunId"),c.runId)
            val plays=proof.getJSONArray("actualPlaybacks")
            repeat(plays.length()){index->val p=plays.getJSONObject(index)
                assertFalse(c.contextPipeline.validateTicket(p.getString("playbackRequestId"),p.getString("musicId")))
                assertTrue(c.contextPipeline.ledger.records().any{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==p.getString("playbackSessionId")})}
            assertEquals(original,hash(ledgerFile()))
            assertEquals(files,caseRoot.walkTopDown().filter{it.isFile}.associate{it.absolutePath to hash(it)})
            File(root,"fusion-restart.json").writeText(JSONObject().put("passed",true).put("caseCount",repo.cases().size)
                .put("processId",android.os.Process.myPid()).put("previousProcessId",proof.getInt("processId")).put("bytesUnchanged",true).toString())
        } finally {close(b)}
    }
}
