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

class LedgerPersonalizationIntegrationTest {
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

    private fun profileFile(modelRoot:File)=modelRoot.resolve("recommendation-model/preferences").walkTopDown().single{it.isFile && it.extension=="jsonl"}
    @Test fun sixSimulatedCommandsUpdateCbrAndExistingProfileWithFrozenContextAndDurableDeduplication() {
        val b=connect()
        try {
            val library=request{b.getLibraryRoot(null)}.value!!
            val catalog=request{b.getChildren(library.mediaId,0,100,null)}.value!!.map{Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())}
            val c=RegistryPlaybackTestService.activeContainer!!
            main{c.startCollection();b.volume=0f}
            val drive=c.driveSessions.currentDriveSessionId()!!
            c.drivingContext.attach("profile4b");c.drivingContext.connection("profile4b",CarConnectionSnapshot(CarConnectionState.CONNECTED,9400))
            repeat(9){Thread.sleep(1000);c.speedSamples.accept(0f,SystemClock.elapsedRealtime(),null)}
            val modelRoot=File(root,"profile4b-isolated/"+UUID.randomUUID())
            val destination=File(modelRoot,"models/clap500");assertTrue(destination.mkdirs())
            for(name in listOf("manifest.json","embeddings.bin"))File(root,"model-fixture/models/clap500/$name").copyTo(File(destination,name))
            var frozen:Long?=null
            fun adapter(user:String=c.localIdentity.id)=RecommendationModelAdapter(modelRoot,user,contextElapsedMs={frozen ?: SystemClock.elapsedRealtime()},contextRunId={c.runId})
            val model=adapter();assertTrue(model.load());model.initialize(setOf("pop"),setOf("happy"))
            val staleInstance=adapter();assertTrue(staleInstance.load())
            val cases=ExperienceCaseRepository.forUser(modelRoot,c.localIdentity.id)
            val runtime=FusionRuntime(CuratedMusicTextEncoder(context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}),cases)
            val port=Media3VoicePlaybackPort(b,c){catalog}
            val phases=mutableListOf<String>();val callbacks=mutableListOf<String>()
            val executor=VoiceExecutionCoordinator(c.localIdentity.id,c.runId,{c.driveSessions.currentDriveSessionId()},c.contextPipeline,model,runtime,
                port,SystemClock::elapsedRealtime,onPhase={phases+=it.name}){callbacks+=it.json()}
            val sequence=listOf("글레오, 잔잔한 음악 틀어줘.","글레오, 좀 더 신나는 노래로 바꿔줘.",
                "글레오, 다음 곡.","글레오, 잠깐 멈춰줘.","글레오, 이 노래 좋아.","글레오, 이 노래는 싫어.")
            fun execute(text:String):GleoVoiceContract.Callback {
                val p=KoreanMusicIntentParser.parse(text);assertNotNull(p.intent)
                val function=when(p.intent){CommandIntent.RECOMMEND_AND_PLAY,CommandIntent.CHANGE_MOOD->GleoVoiceContract.REQUEST
                    CommandIntent.LIKE,CommandIntent.DISLIKE->GleoVoiceContract.RATE;else->GleoVoiceContract.CONTROL}
                return executor.execute(GleoVoiceContract.Action(function,p.intent!!,if(function==GleoVoiceContract.REQUEST)text else null),
                    CommandSource.SIMULATED_GLEO_ACTION,UUID.randomUUID().toString(),SystemClock.elapsedRealtime()).also{assertEquals("success",it.status)}
            }
            sequence.take(4).forEach(::execute)
            assertEquals(0,cases.cases().size)
            val file=profileFile(modelRoot);val beforeFeedbackHash=hash(file)
            val target=port.current();assertFalse(target.playing)
            val command=VoiceCommand.testText(c.localIdentity.id,c.runId,drive,CommandIntent.RECOMMEND_AND_PLAY,sequence[1],Instant.now().toString(),SystemClock.elapsedRealtime())
            val fixed=c.contextSnapshots.capture(drive,command);frozen=fixed.capturedElapsedMs
            val canonical=catalog.filter{it.id.startsWith("jamendo_api_")}
            fun rank(which:RecommendationModelAdapter=model)=which.recommend(canonical,MusicScope.THREE,emptySet(),emptySet(),0,null,drive,
                snapshot=fixed,fusion=runtime,utterance=sequence[1])
            val baseline=rank()
            fun storedVector():FloatArray {val a=JSONObject(file.readLines().last()).getJSONArray("vector");return FloatArray(512){a.getDouble(it).toFloat()}}
            val pBefore=storedVector()
            val manifest=JSONObject(File(destination,"manifest.json").readText());val metadata=manifest.getJSONArray("tracks")
            val embeddingModel=MusicEmbeddingRepository.load(File(destination,"embeddings.bin").readBytes(),
                (0 until metadata.length()).map{val r=metadata.getJSONObject(it);EmbeddingTrack(r.getString("musicId"))},
                manifest.getString("modelVersion"),manifest.getString("sha256"))
            val like=execute(sequence[4]);val likeId=like.data.single().getValue("feedbackEventId") as String
            assertEquals(ProfileFeedbackOutcome.APPLIED_FIXED_RULE.name,like.data.single()["profileUpdate"])
            val positive=rank();val id=target.musicId!!
            val p0=baseline.fusionDecision!!.scores.single{it.musicId==id}.profileCosine
            val p1=positive.fusionDecision!!.scores.single{it.musicId==id}.profileCosine
            val expectedLike=PreferenceMath.update(pBefore,embeddingModel.vector(id)!!,1.0)
            expectedLike.zip(storedVector().toList()).forEach{assertEquals(it.first.toDouble(),it.second.toDouble(),1e-6)}
            assertTrue(p1>p0)
            assertTrue(positive.fusionDecision!!.scores.single{it.musicId==id}.cbr>baseline.fusionDecision!!.scores.single{it.musicId==id}.cbr)
            assertEquals(positive.scores,rank(staleInstance).scores) // Another loaded repository must refresh after a committed append.
            val likeHash=hash(file)
            assertEquals(ProfileFeedbackOutcome.ALREADY_APPLIED,staleInstance.applyExplicitProfileFeedback(c.contextPipeline.ledger,likeId))
            assertEquals(likeHash,hash(file))
            val dislike=execute(sequence[5]);val dislikeId=dislike.data.single().getValue("feedbackEventId") as String
            val negative=rank()
            val expectedDislike=PreferenceMath.update(expectedLike,embeddingModel.vector(id)!!,-.5)
            expectedDislike.zip(storedVector().toList()).forEach{assertEquals(it.first.toDouble(),it.second.toDouble(),1e-6)}
            assertTrue(negative.fusionDecision!!.scores.single{it.musicId==id}.profileCosine<p1)
            assertTrue(negative.fusionDecision!!.scores.single{it.musicId==id}.cbr<positive.fusionDecision!!.scores.single{it.musicId==id}.cbr)
            assertEquals(baseline.scores.keys,positive.scores.keys);assertEquals(baseline.scores.keys,negative.scores.keys)
            assertEquals(1,cases.cases().size);assertEquals(-1.0,cases.cases().single().reward,0.0)
            val finalHash=hash(file)
            val failures=java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val workers=(1..6).map{Thread{try{assertEquals(ProfileFeedbackOutcome.ALREADY_APPLIED,adapter().applyExplicitProfileFeedback(c.contextPipeline.ledger,dislikeId))}catch(t:Throwable){failures+=t}}}
            workers.forEach{it.start()};workers.forEach{it.join(10000)};assertTrue(workers.none{it.isAlive});assertTrue(failures.isEmpty())
            assertEquals(finalHash,hash(file))
            val replay=model.replayExplicitProfileFeedback(c.contextPipeline.ledger,c.runId)
            assertEquals(2,replay.size);assertTrue(replay.values.all{it==ProfileFeedbackOutcome.ALREADY_APPLIED})
            assertEquals(finalHash,hash(file))
            val neutral=c.contextPipeline.ledger.records().filter{it.ids.appRunId==c.runId && it.kind==LedgerKind.LISTENING_FEEDBACK && it.payload.getString("kind") in setOf("PAUSE","NEXT")}
            assertEquals(2,neutral.size)
            neutral.forEach{assertEquals(ProfileFeedbackOutcome.NEUTRAL_NOT_APPLIED,model.applyExplicitProfileFeedback(c.contextPipeline.ledger,it.ids.feedbackEventId!!))}
            assertEquals(finalHash,hash(file))
            assertTrue(runCatching{adapter("wrong-user").applyExplicitProfileFeedback(c.contextPipeline.ledger,dislikeId)}.isFailure)
            val tampered=File(modelRoot,"wrong-playback-ledger.jsonl")
            tampered.writeText(ledgerFile().readLines().joinToString("\n",postfix="\n"){line->
                val row=JSONObject(line)
                if(row.getJSONObject("ids").optString("feedbackEventId")==dislikeId)
                    row.getJSONObject("ids").put("playbackSessionId","wrong-playback-session")
                row.toString()})
            assertTrue(runCatching{model.applyExplicitProfileFeedback(PipelineLedger(tampered),dislikeId)}.isFailure)
            assertEquals(finalHash,hash(file))
            val persisted=JSONObject(file.readLines().last());assertEquals(2,persisted.getInt("learnedCount"))
            val vector=persisted.getJSONArray("vector");val norm=kotlin.math.sqrt((0 until vector.length()).sumOf{vector.getDouble(it)*vector.getDouble(it)})
            assertEquals(1.0,norm,1e-5)
            assertTrue(phases.containsAll(listOf("RECEIVED","RECOMMENDING","PLAYBACK_CONFIRMED")))
            runBlocking{withTimeout(10000){c.eventPublisher.awaitSubmittedWrites()}}
            File(root,"profile4b-proof.json").writeText(JSONObject().put("passed",true).put("appRunId",c.runId).put("driveSessionId",drive)
                .put("localUserId",c.localIdentity.id).put("modelRoot",modelRoot.absolutePath).put("profileFile",file.absolutePath)
                .put("processId",android.os.Process.myPid()).put("likeId",likeId).put("dislikeId",dislikeId).put("musicId",id)
                .put("playbackSessionId",target.playbackSessionId).put("baseline",JSONObject(baseline.fusionDecision!!.toMap()))
                .put("afterLike",JSONObject(positive.fusionDecision!!.toMap())).put("afterDislike",JSONObject(negative.fusionDecision!!.toMap()))
                .put("snapshot",JSONObject(fixed.toMap())).put("phases",org.json.JSONArray(phases)).put("callbacks",org.json.JSONArray(callbacks.map(::JSONObject)))
                .put("profileHashBefore",beforeFeedbackHash).put("profileHashFinal",finalHash).put("learnedCount",2)
                .put("source","SIX_SIMULATED_TEXT_ACTIONS_REAL_GALAXY_MEDIA3_ISOLATED_PROFILE").put("officialVoiceVerified",false).toString(2))
            frozen=null;main{c.stopCollection()}
        }finally{close(b)}
    }
    @Test fun profileUpdateReceiptsRestoreAndRejectDuplicateAfterNewProcess() {
        val proof=JSONObject(File(root,"profile4b-proof.json").readText())
        assertNotEquals(proof.getInt("processId"),android.os.Process.myPid())
        val file=File(proof.getString("profileFile"));val before=hash(file)
        val model=RecommendationModelAdapter(File(proof.getString("modelRoot")),proof.getString("localUserId"))
        assertTrue(model.load())
        val ledger=PipelineLedger(ledgerFile())
        for(id in listOf(proof.getString("likeId"),proof.getString("dislikeId")))assertEquals(ProfileFeedbackOutcome.ALREADY_APPLIED,model.applyExplicitProfileFeedback(ledger,id))
        assertEquals(before,hash(file));assertEquals(proof.getString("profileHashFinal"),before)
        File(root,"profile4b-restart.json").writeText(JSONObject().put("passed",true).put("processId",android.os.Process.myPid())
            .put("previousProcessId",proof.getInt("processId")).put("profileBytesUnchanged",true).toString())
    }
}
