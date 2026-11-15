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

class GleoVoiceContractIntegrationTest {
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
    @Test fun unwiredProbeDoesNotInitializeProductionOrPretendToHearVoice() {
        assertNull(GleoContractProbeReceiver.handler)
        context.sendBroadcast(action(GleoVoiceContract.CONTROL,"PLAY"))
        Thread.sleep(300)
        val app=context.applicationContext as GleoApplication
        val lazy=GleoApplication::class.java.getDeclaredField("container\$delegate").apply{isAccessible=true}.get(app) as Lazy<*>
        assertFalse(lazy.isInitialized())
        assertNull(GleoContractProbeReceiver.handler)
    }



    private fun action(function:String,operation:String,text:String?=null,packed:Boolean=true):Intent {
        val intent=Intent(context,GleoContractProbeReceiver::class.java).setAction(function)
        if(packed) {
            val j=JSONObject().put("operation",operation);if(text!=null)j.put("musicRequest",text)
            intent.putExtra("ACTION_PARAM",j.toString())
        } else {intent.putExtra("operation",operation);if(text!=null)intent.putExtra("musicRequest",text)}
        return intent
    }
    @Test fun officialSchemaAndDocumentedInputVariantsAreStrictAndCallbackIsNotAcceptance() {
        val schema=JSONObject(context.assets.open("schema.json").bufferedReader().use{it.readText()})
        assertEquals(context.packageName,schema.getString("packageName"));assertEquals(3,schema.getJSONArray("actionCalls").length())
        val names=(0..2).map {schema.getJSONArray("actionCalls").getJSONObject(it).getJSONObject("function").getString("name")}.toSet()
        assertEquals(setOf(GleoVoiceContract.REQUEST,GleoVoiceContract.CONTROL,GleoVoiceContract.RATE),names)
        for(packed in listOf(true,false))assertEquals(CommandIntent.NEXT,GleoVoiceContract.decode(action(GleoVoiceContract.CONTROL,"NEXT",packed=packed)).intent)
        val invalid=listOf(Intent("UNKNOWN"),action(GleoVoiceContract.CONTROL,"LIKE"),action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY"),
            action(GleoVoiceContract.CONTROL,"NEXT").putExtra("operation","PAUSE"),
            Intent(GleoVoiceContract.CONTROL).putExtra("ACTION_PARAM","not-json"),
            Intent(GleoVoiceContract.CONTROL).putExtra("operation",5),
            action(GleoVoiceContract.CONTROL,"NEXT").putExtra("unknown","value"))
        invalid.forEach {assertTrue(runCatching{GleoVoiceContract.decode(it)}.isFailure)}
        val prototype=context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}
        val encoder=CuratedMusicTextEncoder(prototype)
        assertEquals("CURATED_ENGLISH_PRECOMPUTED_LOOKUP",encoder.encode("잔잔한 음악 틀어줘")!!.processing)
        assertNull(encoder.encode("잘 모르겠어"))
        assertTrue(runCatching{CuratedMusicTextEncoder(prototype+" ")}.isFailure)
        val cb=GleoVoiceContract.Callback(GleoVoiceContract.REQUEST,"fail","Playback could not be confirmed.")
        val intent=cb.intent(context.packageName)
        assertEquals("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE",intent.action)
        assertEquals(context.packageName,intent.getStringExtra("packageName"))
        val j=JSONObject(intent.getStringExtra("response")!!)
        assertEquals("fail",j.getString("status"));assertEquals("silent",j.getString("mode"))
        assertEquals(0,org.json.JSONArray(j.getString("data")).length())
        // No production exported endpoint and no receiving Gleo runtime are inferred from serialization.
        val info=context.packageManager.getReceiverInfo(ComponentName(context,GleoContractProbeReceiver::class.java),0)
        assertFalse(info.exported)
    }
    @Test fun documentedCallbackKeysStatusesAndManifestFiltersRemainConsistent() {
        for(status in listOf("success","asking","fail")) {
            val cb=GleoVoiceContract.Callback(GleoVoiceContract.RATE,status,"The requested music feedback is available.",
                listOf(mapOf("musicId" to "jamendo_api_1204669","title" to "한국어 제목")))
            val intent=cb.intent(context.packageName)
            assertEquals(setOf("packageName","response"),intent.extras!!.keySet())
            assertFalse(intent.hasExtra("result")) // This key is not part of the six checked documents' callback transport.
            val body=JSONObject(intent.getStringExtra("response")!!)
            assertEquals(status,body.getString("status"));assertTrue(body.get("data") is String)
            assertEquals("한국어 제목",org.json.JSONArray(body.getString("data")).getJSONObject(0).getString("title"))
        }
        assertTrue(runCatching{GleoVoiceContract.Callback(GleoVoiceContract.RATE,"error","The action failed.")}.isFailure)
        assertTrue(runCatching{GleoVoiceContract.Callback(GleoVoiceContract.RATE,"fail","")}.isFailure)
        assertTrue(runCatching{GleoVoiceContract.Callback(GleoVoiceContract.RATE,"fail","실패했습니다.")}.isFailure)
        assertTrue(runCatching{GleoVoiceContract.Callback(GleoVoiceContract.RATE,"fail","The action failed.").intent("")}.isFailure)
        assertTrue(runCatching{GleoVoiceContract.decode(Intent(GleoVoiceContract.CONTROL)
            .putExtra("actionParameters","{\"operation\":\"NEXT\"}"))}.isFailure)
        try {
            for((function,operation) in listOf(GleoVoiceContract.REQUEST to "RECOMMEND_AND_PLAY",GleoVoiceContract.CONTROL to "PAUSE",GleoVoiceContract.RATE to "LIKE")) {
                val latch=java.util.concurrent.CountDownLatch(1)
                GleoContractProbeReceiver.handler={assertEquals(function,it.function);latch.countDown()}
                val intent=Intent(function).setPackage(context.packageName).putExtra("operation",operation)
                if(function==GleoVoiceContract.REQUEST)intent.putExtra("musicRequest","잔잔한 음악 틀어줘")
                context.sendBroadcast(intent) // Implicit action + explicit package: the declared filter must actually match.
                assertTrue("declared function filter $function",latch.await(3,TimeUnit.SECONDS))
            }
        }finally{GleoContractProbeReceiver.handler=null}
    }

    @Test fun simulatedDocumentedActionRunsKoreanPrototypesMedia3CallbackAndExplicitLearning() {
        val b=connect()
        try {
            val library=request{b.getLibraryRoot(null)}.value!!
            val catalog=request{b.getChildren(library.mediaId,0,100,null)}.value!!.map {
                Track(it.mediaId,it.mediaMetadata.title.toString(),it.mediaMetadata.artist.toString())}
            val c=RegistryPlaybackTestService.activeContainer!!
            main{c.startCollection();b.volume=0f}
            val drive=c.driveSessions.currentDriveSessionId()!!
            c.drivingContext.attach("voice-test");c.drivingContext.connection("voice-test",CarConnectionSnapshot(CarConnectionState.CONNECTED,9004))
            repeat(9){Thread.sleep(1000);c.speedSamples.accept(0f,SystemClock.elapsedRealtime(),null)}
            val model=RecommendationModelAdapter(File(root,"model-fixture"),c.localIdentity.id,contextRunId={c.runId})
            assertTrue(model.load());if(!model.availability(catalog.map{it.id}.toSet()).hasPreference)model.initialize(setOf("pop"),setOf("happy"))
            val caseRoot=File(root,"voice-isolated/"+UUID.randomUUID())
            val cases=ExperienceCaseRepository.forUser(caseRoot,c.localIdentity.id)
            val runtime=FusionRuntime(CuratedMusicTextEncoder(context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}),cases)
            val port=Media3VoicePlaybackPort(b,c){catalog}
            val callbacks=java.util.Collections.synchronizedList(mutableListOf<Map<String,Any?>>())
            val timings=mutableListOf<Map<String,Any?>>()
            val executor=VoiceExecutionCoordinator(c.localIdentity.id,c.runId,{c.driveSessions.currentDriveSessionId()},c.contextPipeline,model,runtime,
                port,SystemClock::elapsedRealtime,applyProfileFeedback=false) {cb->
                val state=port.current()
                if(cb.functionName==GleoVoiceContract.REQUEST && cb.status=="success")assertTrue(state.playing && state.positionMs>=250)
                callbacks+=mapOf("callback" to JSONObject(cb.json()),"musicIdAtCallback" to state.musicId,
                    "playbackSessionIdAtCallback" to state.playbackSessionId,"playingAtCallback" to state.playing)
            }
            fun invoke(intent:Intent,token:String=UUID.randomUUID().toString()):GleoVoiceContract.Callback {
                val latch=java.util.concurrent.CountDownLatch(1);var result:GleoVoiceContract.Callback?=null;var error:Throwable?=null
                val start=SystemClock.elapsedRealtime()
                GleoContractProbeReceiver.handler={a->try {result=executor.execute(a,CommandSource.SIMULATED_GLEO_ACTION,token,start)}catch(t:Throwable){error=t}finally{latch.countDown()}}
                context.sendBroadcast(intent)
                assertTrue("same-UID receiver result",latch.await(8,TimeUnit.SECONDS));error?.let{throw it}
                assertTrue("callback under platform deadline",SystemClock.elapsedRealtime()-start<7000)
                timings+=mapOf("function" to intent.action,"status" to result!!.status,"elapsedMs" to SystemClock.elapsedRealtime()-start,
                    "localDeliveryToken" to token)
                return result!!
            }
            val initialIntent=action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY","글레오, 잔잔한 음악 틀어줘")
            val first=invoke(initialIntent,"first-delivery");assertEquals("success",first.status)
            val firstCallbackCount=callbacks.size
            assertEquals(first,invoke(initialIntent,"first-delivery"));assertEquals(firstCallbackCount,callbacks.size)
            val initialRecords=c.contextPipeline.ledger.records().filter{it.ids.appRunId==c.runId}
            val baseline=initialRecords.last{it.kind==LedgerKind.RECOMMENDATION_RESULT}
            assertEquals("REAL_CLAP_PRECOMPUTED_CURATED_ENGLISH",baseline.payload.getJSONObject("experimentalFusion").getString("textStatus"))
            assertEquals("success",invoke(action(GleoVoiceContract.REQUEST,"CHANGE_MOOD","글레오, 좀 더 신나는 노래",false)).status)
            val energetic=c.contextPipeline.ledger.records().last{it.kind==LedgerKind.RECOMMENDATION_RESULT}
            assertNotEquals(baseline.payload.getJSONObject("experimentalFusion").getString("encodedText"),energetic.payload.getJSONObject("experimentalFusion").getString("encodedText"))
            assertEquals("success",invoke(action(GleoVoiceContract.RATE,"LIKE")).status)
            assertEquals(1,cases.cases().size)
            val liked=cases.cases().single().musicId
            assertEquals("success",invoke(action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY","좀 더 신나는 노래")).status)
            val after=c.contextPipeline.ledger.records().last{it.kind==LedgerKind.RECOMMENDATION_RESULT}
            val scores=after.payload.getJSONObject("experimentalFusion").getJSONArray("scores")
            assertTrue((0 until scores.length()).map{scores.getJSONObject(it)}.single{it.getString("musicId")==liked}.getJSONObject("contributions").getDouble("cbr")>0)
            assertEquals("success",invoke(action(GleoVoiceContract.RATE,"DISLIKE")).status)
            assertTrue(cases.cases().any{it.reward<0})
            assertEquals("success",invoke(action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY","좀 더 신나는 노래")).status)
            val afterDislike=c.contextPipeline.ledger.records().last{it.kind==LedgerKind.RECOMMENDATION_RESULT}
            val negativeScores=afterDislike.payload.getJSONObject("experimentalFusion").getJSONArray("scores")
            val priorCbr=(0 until scores.length()).map{scores.getJSONObject(it)}.single{it.getString("musicId")==liked}.getJSONObject("contributions").getDouble("cbr")
            val revisedCbr=(0 until negativeScores.length()).map{negativeScores.getJSONObject(it)}.single{it.getString("musicId")==liked}.getJSONObject("contributions").getDouble("cbr")
            assertTrue("explicit dislike changes next recommendation component",revisedCbr<priorCbr)
            assertEquals("success",invoke(action(GleoVoiceContract.CONTROL,"PAUSE")).status)
            assertEquals("success",invoke(action(GleoVoiceContract.CONTROL,"PLAY")).status)
            val explicitCount=cases.cases().size
            assertEquals("success",invoke(action(GleoVoiceContract.CONTROL,"NEXT")).status)
            assertEquals("success",invoke(action(GleoVoiceContract.CONTROL,"PREVIOUS")).status)
            assertEquals(explicitCount,cases.cases().size)
            val rain=invoke(action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY","비 오는 퇴근길에 어울리는 음악 틀어줘"))
            assertEquals("success",rain.status)
            val snapshot=c.contextPipeline.ledger.records().last{it.kind==LedgerKind.CONTEXT_SNAPSHOT}
            assertEquals("USER_STATEMENT",snapshot.payload.getJSONObject("voice").getJSONObject("userClaims").getString("origin"))
            assertEquals("MISSING",snapshot.payload.getJSONObject("timeWeather").getJSONObject("weather").getString("freshness"))
            assertEquals("asking",invoke(action(GleoVoiceContract.REQUEST,"RECOMMEND_AND_PLAY","잘 모르겠어")).status)
            val official=executor.execute(GleoVoiceContract.decode(initialIntent),CommandSource.OFFICIAL_GLEO_ACTION,"not-official",SystemClock.elapsedRealtime())
            assertEquals("fail",official.status)
            val attempts=java.util.Collections.synchronizedList(mutableListOf<GleoVoiceContract.Callback>())
            val started=java.util.concurrent.CountDownLatch(1)
            val nonStartingPort=object:VoicePlaybackPort by port {
                override fun start(ticket:PlaybackTicket){started.countDown()} // negative test: request accepted, actual start withheld
            }
            val timeoutExecutor=VoiceExecutionCoordinator(c.localIdentity.id,c.runId,{c.driveSessions.currentDriveSessionId()},c.contextPipeline,
                model,runtime,nonStartingPort,SystemClock::elapsedRealtime){attempts+=it}
            val timeoutStart=SystemClock.elapsedRealtime()
            val timeoutThread=Thread{timeoutExecutor.execute(GleoVoiceContract.decode(initialIntent),CommandSource.SIMULATED_GLEO_ACTION,"timeout",timeoutStart)}
            timeoutThread.start();assertTrue(started.await(2,TimeUnit.SECONDS))
            val busy=timeoutExecutor.execute(GleoVoiceContract.decode(action(GleoVoiceContract.CONTROL,"NEXT")),CommandSource.SIMULATED_GLEO_ACTION,"busy",SystemClock.elapsedRealtime())
            assertEquals("fail",busy.status)
            timeoutThread.join(7500);assertFalse(timeoutThread.isAlive)
            val timeoutElapsedMs=SystemClock.elapsedRealtime()-timeoutStart
            assertEquals(2,attempts.size);assertTrue(attempts.all{it.status=="fail"})
            assertTrue(timeoutElapsedMs<7000)
            runBlocking{withTimeout(10000){c.eventPublisher.awaitSubmittedWrites()}}
            val records=c.contextPipeline.ledger.records().filter{it.ids.appRunId==c.runId}
            val bound=records.filter{it.kind==LedgerKind.PLAYBACK_BOUND}
            bound.forEach {row->assertTrue(rows(c.runId).any{it.optString("playbackSessionId")==row.ids.playbackSessionId &&
                it.optJSONObject("event")?.getString("label")=="STARTED" && it.optJSONObject("event")?.optJSONObject("music")?.optString("musicId")==row.ids.musicId})}
            File(root,"voice-step4-proof.json").writeText(JSONObject().put("passed",true).put("appRunId",c.runId).put("driveSessionId",drive)
                .put("source","SAME_UID_DOCUMENTED_INTENT_SIMULATION_NOT_GLEO_VOICE").put("callbacks",org.json.JSONArray(callbacks))
                .put("timings",org.json.JSONArray(timings))
                .put("negativeTimeoutMs",timeoutElapsedMs).put("timeoutAndBusyCallbacks",attempts.size)
                .put("caseCount",cases.cases().size).put("caseRoot",caseRoot.absolutePath).put("actualPlaybackCount",bound.size)
                .put("boundMusicIds",org.json.JSONArray(bound.map{it.ids.musicId}.distinct())).put("officialGleoVerified",false)
                .put("androidNeuralTextInference",false).put("precomputedRealClapPrototypes",true).toString(2))
            main{c.stopCollection()}
        } finally {GleoContractProbeReceiver.handler=null;close(b)}
    }
}
