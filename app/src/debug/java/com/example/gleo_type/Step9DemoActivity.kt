package com.example.gleo_type

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.gleo_type.music.RegistryPlaybackTestService
import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Debug-only, DUMP-protected entry. Isolated journals/profile/Hearts, existing approved audio by symlink. */
class Step9DemoActivity:ComponentActivity() {
 private var port:MediaPlaybackConnection?=null
 private var owner:AppContainer?=null
 private var executor:VoiceExecutionCoordinator?=null
 private var plan by mutableStateOf<RecommendationPlan?>(null)
 private var message by mutableStateOf("초기화 중 · 실제 사용자 기록 사용 안함")
 private var ready by mutableStateOf(false)
 private var busy by mutableStateOf(false)
 private var heartCount by mutableIntStateOf(0)
 private var showContext by mutableStateOf(false)
 private var showHearts by mutableStateOf(false)
 private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val name=savedInstanceState?.getString("demoRoot") ?: "step9-demo-${UUID.randomUUID()}"
  require(name.matches(Regex("step9-demo-[a-f0-9-]{36}")));root=File(cacheDir,name).apply{mkdirs()}
  setContent{MaterialTheme{Surface{Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text("Gleo Action 모의 실행 — 공식 음성 연동 미검증",style=MaterialTheme.typography.titleMedium)
   Text("안전한 스마트폰/정차 개발 시연 · SDK 미수신은 unknown · 사용자 기록 분리",style=MaterialTheme.typography.bodySmall)
   Text(message,Modifier.testTag("step9_status"),maxLines=3)
   val state=port?.state?.collectAsState()?.value ?: PlaybackUi()
   Text("${state.track?.title ?: "음악 없음"} · ${if(state.playing)"실제 Media3 재생" else "일시정지/대기"}",Modifier.testTag("step9_playback"))
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick={execute("신나는 노래 틀어줘")},enabled=ready&&!busy,modifier=Modifier.weight(1f).testTag("step9_energetic")){Text("신나는 노래")}
    Button(onClick={execute("다른 노래 추천해 줘")},enabled=ready&&!busy,modifier=Modifier.weight(1f).testTag("step9_alternative")){Text("다른 노래")}}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick={execute("이 노래 기억해 줘")},enabled=ready&&!busy&&state.playing,modifier=Modifier.testTag("step9_remember")){Text("Hearts 저장")}
    TextButton(onClick={showContext=!showContext;showHearts=false},modifier=Modifier.testTag("step9_context")){Text("6대 맥락")}
    TextButton(onClick={showHearts=!showHearts;showContext=false},modifier=Modifier.testTag("step9_hearts")){Text("Hearts $heartCount")}}
   if(showContext)ContextDiagnostics(plan?.snapshot,plan?.run,Modifier.weight(1f))
   else if(showHearts) {
    var pins by remember{mutableStateOf<List<Pin>>(emptyList())}
    LaunchedEffect(heartCount){pins=withContext(Dispatchers.IO){owner?.runtimeArchives?.load().orEmpty()}}
    pins.forEach{p->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(p.track?.title ?: "음악 미확인");Text("주행 중 즉시 저장 · ${p.runtimeHeart?.captures?.size ?: 0}회 · 위치 ${if(p.place==null)"미확인" else "좌표 비공개"}");Text("로컬 저장 · 제품 동기화 미확인")}}}
   } else {Text("REQUEST_MUSIC · CONTROL_PLAYBACK · RATE_MUSIC — 기존 Schema 3개 유지")
    Text("기억하기는 로컬 Hearts 명령이며 기존 Schema function을 추가/변경하지 않았습니다")}
   Row {TextButton(onClick={port?.toggle()},enabled=ready){Text("재생/일시정지")}
    TextButton(onClick={finish()},modifier=Modifier.testTag("step9_finish")){Text("시연 종료")}}
  }}}}
  lifecycleScope.launch {runCatching{setup()}.onFailure{message="시연 초기화 실패 · 운영 모델/기존 승인 cache 3곡 준비 상태 확인"}}
 }
 private lateinit var root:File
 private suspend fun setup() {
  val audio=File(cacheDir,"audio-registry-approved-samples/audio-assets")
  withContext(Dispatchers.IO){check(audio.isDirectory);val link=File(root,"audio-assets");if(!link.exists())android.system.Os.symlink(audio.absolutePath,link.absolutePath)}
  RegistryPlaybackTestService.testRoot=root
  val discovery=androidx.media3.session.MediaBrowser.Builder(this,androidx.media3.session.SessionToken(this,ComponentName(this,RegistryPlaybackTestService::class.java))).buildAsync()
  val browser=withContext(Dispatchers.IO){discovery.get(15,java.util.concurrent.TimeUnit.SECONDS)}
  // Exactly the service-owned container; the temporary discovery controller adds no telemetry collector.
  val c=RegistryPlaybackTestService.activeContainer!!;owner=c
  port=MediaPlaybackConnection(applicationContext,c,ComponentName(this,RegistryPlaybackTestService::class.java))
  withTimeout(15000){while(port?.state?.value?.connected!=true||port?.catalog?.value.isNullOrEmpty())delay(50)}
  browser.release()
  c.startCollection()
  val model=withContext(Dispatchers.IO){
   val linked=File(root,"models/clap500").apply{mkdirs()}
   listOf("manifest.json","embeddings.bin").forEach{name->val f=File(linked,name);if(!f.exists())android.system.Os.symlink(File(filesDir,"models/clap500/$name").absolutePath,f.absolutePath)}
   RecommendationModelAdapter(root,c.localIdentity.id,PlaybackMappingVerifier(this@Step9DemoActivity)::verify,
    contextSnapshotProvider={c.contextSnapshots.capture(it)},contextRunId={c.runId},
    operationalRuntime=OperationalRecommendationRuntime(root,c.localIdentity.id,{assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}},{assets.open("audio-assets/catalog-v1.json").bufferedReader().use{it.readText()}})).also{check(it.load());if(!it.availability(port!!.catalog.value.map{it.id}.toSet()).hasPreference)it.initialize(setOf("pop"),setOf("happy"))}
  }
  executor=VoiceExecutionCoordinator(c.localIdentity.id,c.runId,c.driveSessions::currentDriveSessionId,c.contextPipeline,model,
   OperationalRecommendationRuntime(root,c.localIdentity.id,{assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}},{assets.open("audio-assets/catalog-v1.json").bufferedReader().use{it.readText()}}).runtime()!!,
   object:VoicePlaybackPort {
    override fun catalog()=port!!.catalog.value
    override fun current()=port!!.state.value.let{VoicePlaybackObservation(it.track?.id,it.playbackSessionId,it.playing,it.position,it.error!=null)}
    override fun start(ticket:PlaybackTicket){main{port!!.playTicket(ticket)}}
    override fun control(intent:CommandIntent){main{when(intent){CommandIntent.PLAY,CommandIntent.PAUSE->port!!.toggle();CommandIntent.NEXT->port!!.next();CommandIntent.PREVIOUS->port!!.previous();else->error("Unsupported")}}}
   },SystemClock::elapsedRealtime,onRecommendation={p->runOnUiThread{plan=p}},callback={})
  heartCount=withContext(Dispatchers.IO){c.runtimeArchives.load().size};message="준비됨 · 실제 Media3 · 합성 초기취향 pop/happy · 공식 Gleo 아님";ready=true
  File(cacheDir,"step9-demo-root.txt").writeText(root.name)
 }
 private fun main(block:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())block() else {
  val future=java.util.concurrent.FutureTask{block()};runOnUiThread(future);future.get(5,java.util.concurrent.TimeUnit.SECONDS)}}
 private fun execute(text:String){if(busy)return;busy=true
  if(LocalHeartsCommand.matches(text)){port!!.rememberCurrent{result->message=LocalHeartsCommand.DISCLOSURE+" · "+result.message
   lifecycleScope.launch{heartCount=withContext(Dispatchers.IO){owner!!.runtimeArchives.load().size};busy=false}};return}
  val received=SystemClock.elapsedRealtime()
  io.execute{val result=executor!!.execute(GleoVoiceContract.Action(GleoVoiceContract.REQUEST,CommandIntent.RECOMMEND_AND_PLAY,text),CommandSource.SIMULATED_GLEO_ACTION,UUID.randomUUID().toString(),received)
   runOnUiThread{message="${result.functionName} · ${result.status} · ${result.message}";busy=false}}
 }
 override fun onSaveInstanceState(out:Bundle){out.putString("demoRoot",root.name);super.onSaveInstanceState(out)}
 override fun onDestroy(){port?.close();io.shutdown();if(isFinishing){owner?.stopCollection();stopService(Intent(this,RegistryPlaybackTestService::class.java))};super.onDestroy()}
}
