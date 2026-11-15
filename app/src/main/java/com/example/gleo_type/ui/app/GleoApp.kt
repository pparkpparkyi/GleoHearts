@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.gleo_type.ui.app

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gleo_type.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private val tabs = listOf("홈", "음악", "캘린더", "Hearts", "마이")

@Composable fun GleoApp(container: AppContainer, onStartCollection: () -> Unit,
                        onStopCollection: () -> Unit, onDiagnostics: () -> Unit,
                        carConnection: () -> com.example.gleo_type.vehicle.CarConnectionSnapshot = { com.example.gleo_type.vehicle.CarConnectionSnapshot() }) {
    val context = LocalContext.current
    val connection = remember { MediaPlaybackConnection(context.applicationContext, container) }
    DisposableEffect(connection) { onDispose { connection.close() } }
    val inbox=remember(container){com.example.gleo_type.sync.DurableReviewInbox(File(context.filesDir,"sync/v1/inbox.jsonl"),
        container.localIdentity.id,container.reviewSync.identity)}
    val history = remember(container) { com.example.gleo_type.sync.ReceivedDriveHistory(
        JsonlHistoryRepository(File(context.filesDir,"events"),container.localIdentity.id),inbox) }
    val archives = remember { LocalArchiveRepository(File(context.filesDir, "archive")) }
    val automotive=remember {context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)}
    val uxMonitor = remember { if(automotive)CarDrivingUxMonitor(context.applicationContext) else null }
    DisposableEffect(uxMonitor) { onDispose { uxMonitor?.close() } }
    val ux by (uxMonitor?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION")) }).collectAsState()
    val feedback = remember { LocalRecommendationFeedback(File(context.filesDir, "feedback"), container.localIdentity.id) }
    val vehicle=remember(container,carConnection){CarV10StateAdapter(carConnection,
        container.driveSessions::currentDriveSessionId,container.drivingContext::current,SystemClock::elapsedRealtime,container.runId)}
    val recommendationHistory=remember{LocalRecommendationHistory(File(context.filesDir,"recommendations"))}
    val modelAdapter=remember(container){com.example.gleo_type.recommendation.RecommendationModelAdapter(context.filesDir,container.localIdentity.id,
        com.example.gleo_type.recommendation.PlaybackMappingVerifier(context)::verify,
        drivingContext=container.drivingContext::current,
        drivingPolicy=runCatching{com.example.gleo_type.recommendation.DrivingScorePolicy(context.getSharedPreferences("gleo_experiments",Context.MODE_PRIVATE)
            .getString("driving_recommendation_weight","0.05")!!.toDouble())}.getOrDefault(com.example.gleo_type.recommendation.DrivingScorePolicy()),
        contextSnapshotProvider={ drive -> container.contextSnapshots.capture(drive) }, contextRunId={container.runId},
        operationalRuntime=com.example.gleo_type.recommendation.OperationalRecommendationRuntime(context.filesDir,container.localIdentity.id,
            {context.assets.open("clap-mood-prototypes-v1.json").bufferedReader().use{it.readText()}},
            {context.assets.open("audio-assets/catalog-v1.json").bufferedReader().use{it.readText()}}))}
    val carMusic=remember(modelAdapter){CarMusicRecommendationSource(modelAdapter,recommendationHistory,
        CarMusicPresentationRepository(context,modelAdapter::modelFingerprint,modelAdapter::modelTrackIds),
        {context.getSharedPreferences("gleo_ui",Context.MODE_PRIVATE).getStringSet("excluded",emptySet()).orEmpty().toSet()},
        appRunId={container.runId},driveId=container.driveSessions::currentDriveSessionId)}
    GleoScreen(connection, history, archives, container.localIdentity.id,
        { container.driveSessions.currentDriveSessionId() }, onStartCollection, onStopCollection, onDiagnostics,
        ux = ux, feedback = feedback, recommendationHistory = recommendationHistory,recommendationModelAdapter=modelAdapter,
        carVehicle=vehicle.takeIf{automotive},carMusic=carMusic,mobileLayout=!automotive,contextSnapshotProvider={container.contextSnapshots.capture(container.driveSessions.currentDriveSessionId())},receivedInbox=inbox)
}

/** Injectable surface for isolated UI tests without touching journal/session internals. */
@Composable fun GleoScreen(player: PlaybackPort, historyRepository: DriveHistoryRepository,
    archiveRepository: ArchiveRepository, localId: String, driveProvider: () -> String?,
    onStartCollection: () -> Unit, onStopCollection: () -> Unit, onDiagnostics: () -> Unit,
    preferenceName: String = "gleo_ui", ux: DrivingUx = DrivingUx(), feedback: RecommendationFeedback = NoFeedback, recommendationHistory: RecommendationHistory? = null,
    recommendationModelAdapter: com.example.gleo_type.recommendation.RecommendationModelAdapter? = null,
    carVehicle:CarV10StateAdapter?=null,carMusic:CarMusicRecommendationSource?=null,mobileLayout:Boolean=false,
    contextSnapshotProvider:(()->com.example.gleo_type.context.ContextSnapshot)?=null,
    receivedInbox:com.example.gleo_type.sync.DurableReviewInbox?=null,photoStore:ArchivePhotoStore?=null,musicPlans:MusicPlanRepository?=null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }
    val tabStateHolder=rememberSaveableStateHolder()
    val localPhotos=photoStore ?: remember(context){ArchivePhotoStore(context.applicationContext,File(context.noBackupFilesDir,"archive-media/photos"))}
    val planRepository=musicPlans ?: remember(localId){LocalMusicPlanRepository(File(context.filesDir,"calendar-plans/music-plans.json"),localId)}
    var photoReviewIds by rememberSaveable {mutableStateOf<List<String>?>(null)}
    var photoEditPinId by rememberSaveable {mutableStateOf<String?>(null)}
    var photoEditorToken by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}

    val recommendations = recommendationHistory ?: remember { MemoryRecommendationHistory() }
    var carMode by rememberSaveable { mutableStateOf(!mobileLayout && carVehicle!=null) }
    var tab by rememberSaveable { mutableIntStateOf(prefs.getInt("tab", 0).coerceIn(0, 4)) }
    var sheet by rememberSaveable { mutableIntStateOf(prefs.getInt("sheet", if(mobileLayout)1 else 0).coerceIn(0, 2)) }
    var dark by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", true)) }
    if(mobileLayout)V12SystemBars(dark)
    var date by rememberSaveable { mutableStateOf(prefs.getString("date", null) ?: LocalDate.now().toString()) }
    var month by rememberSaveable { mutableStateOf(prefs.getString("month", null) ?: YearMonth.now().toString()) }
    var history by remember { mutableStateOf(HistorySnapshot()) }
    var historyReady by remember { mutableStateOf(false) }
    var pins by remember { mutableStateOf<List<Pin>>(emptyList()) }
    var pinsReady by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf(driveProvider()) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val policy = if (ux.source == "CarUxRestrictionsManager" && ux.observedElapsedMs?.let { SystemClock.elapsedRealtime() - it in 0..6000 } != true) DrivingUx() else ux
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(policy.reduced) { if (policy.reduced) { focus.clearFocus(force = true); keyboard?.hide() } }
    var selectedPin by rememberSaveable { mutableStateOf<String?>(null) }
    var summaryWanted by rememberSaveable { mutableStateOf(prefs.getString("pendingSummary", null)) }
    var summaryAutomatic by rememberSaveable { mutableStateOf(true) }
    var reviewStopRequested by rememberSaveable { mutableStateOf(false) }
    var editingPinId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletedPins by remember { mutableStateOf<List<Pin>>(emptyList()) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf("") }
    var draftAt by rememberSaveable { mutableStateOf("") }
    var draftDrive by rememberSaveable { mutableStateOf<String?>(null) }
    var draftDriveKey by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftArtist by rememberSaveable { mutableStateOf("") }
    var draftOptions by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var draftLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var draftLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var draftTags by rememberSaveable { mutableStateOf("") }
    var draftNote by rememberSaveable { mutableStateOf("") }
    var pinBusy by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state by player.state.collectAsState()
    val catalog by player.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun openPin(record: DriveRecord? = null) {
        if (policy.reduced) return
        if(record!=null && (record.startedAt==null || record.endedAt==null || record.durationMs==null)) {
            error="종료가 확인된 주행만 요약에서 저장할 수 있습니다.";return
        }
        val track = if (record == null) state.track else record.tracks.firstOrNull()?.let { id ->
            catalog.firstOrNull { it.id == id } ?: Track(id, id, "메타데이터 미제공")
        }
        val place = if (record != null) record.place else history.location.takeIf {
            drive != null && history.locationDrive == drive && history.locationElapsedMs?.let { nowElapsed - it in 0..15000 } == true
        }
        editingPinId=null
        draftId = UUID.randomUUID().toString(); draftAt = Instant.now().toString()
        draftDrive = record?.id ?: drive; draftDriveKey=record?.key; draftTrackId = track?.id
        draftTitle = track?.title.orEmpty(); draftArtist = track?.artist.orEmpty()
        draftOptions = record?.tracks ?: listOfNotNull(track?.id)
        draftLatitude = place?.latitude; draftLongitude = place?.longitude
        draftTags = ""; draftNote = ""
        creating = true
    }
    fun editPin(pin:Pin) {
        if(policy.reduced || pinBusy)return
        editingPinId=pin.id;draftDriveKey=null;draftDrive=null;draftAt=pin.savedAt;draftId=pin.id;draftTrackId=pin.track?.id;draftTitle=pin.track?.title.orEmpty();draftArtist=pin.track?.artist.orEmpty()
        draftLatitude=pin.place?.latitude;draftLongitude=pin.place?.longitude
        draftTags=pin.tags.joinToString(",");draftNote=pin.note;draftOptions=emptyList();selectedPin=null;creating=true
    }
    fun closeSummary() {
        summaryWanted?.let { id -> prefs.edit().putStringSet("handledSummaries",
            (prefs.getStringSet("handledSummaries", emptySet()).orEmpty() + id).toList().takeLast(256).toSet()).apply() }
        summaryWanted = null; reviewStopRequested=false
    }
    suspend fun refreshPins() {
        runCatching { withContext(Dispatchers.IO) { archiveRepository.load() to archiveRepository.deleted() } }
            .onSuccess { pins = it.first; deletedPins=it.second; pinsReady=true }.onFailure { error = "저장 기록을 읽지 못했습니다. 기존 파일을 보존했습니다." }
    }
    // Runtime Hearts may be written by the player outside this screen's repository callbacks.
    LaunchedEffect(tab, summaryWanted) { refreshPins() }
    LaunchedEffect(historyRepository) {
        while (true) {
            history = withContext(Dispatchers.IO) { historyRepository.refresh() }
            historyReady = true
            drive = driveProvider(); nowElapsed = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    LaunchedEffect(tab, sheet, dark, date, month) {
        prefs.edit().putInt("tab", tab).putInt("sheet", sheet).putBoolean("dark", dark)
            .putString("date", date).putString("month", month).apply()
    }
    LaunchedEffect(summaryWanted) { prefs.edit().putString("pendingSummary", summaryWanted).apply() }
    LaunchedEffect(historyReady, summaryWanted, history.drives) {
        if (summaryAutomatic) {
            val record = history.drives.firstOrNull { it.key == summaryWanted } ?: history.drives.firstOrNull { it.id == summaryWanted }
            if (summaryWanted in prefs.getStringSet("handledSummaries", emptySet()).orEmpty()) summaryWanted = null
            else if (record?.endedAt != null && record.tracks.isEmpty() && record.speedSampleCount == 0 && record.place == null) closeSummary()
        }
    }
    val heartsGate=remember { HeartsEditGate() }
    val heartsSafe=heartsGate.allowed(policy,carVehicle?.snapshot()?.connection,SystemClock.elapsedRealtime(),reviewStopRequested && drive==null)
    val latestHeartsSafe by rememberUpdatedState(policy)
    val latestCatalog by rememberUpdatedState(catalog)
    val latestReviewStop by rememberUpdatedState(reviewStopRequested && drive==null)
    val heartsSaver=remember(archiveRepository,historyRepository,localId) {
        HeartsReviewSaver(archiveRepository,historyRepository,localId,{latestCatalog},{if(mobileLayout)latestHeartsSafe.complexAllowed else heartsGate.allowed(latestHeartsSafe,carVehicle?.snapshot()?.connection,SystemClock.elapsedRealtime(),latestReviewStop)})
    }
    val carSummary=history.drives.firstOrNull { it.key==summaryWanted } ?: history.drives.firstOrNull { it.id==summaryWanted }
    val showCarReview=carSummary?.endedAt!=null && (reviewStopRequested || !summaryAutomatic)
    val colors = if(mobileLayout)handheldColors(dark) else auroraColors(dark)
    BackHandler(selectedPin != null || creating || managing || summaryWanted != null || tab != 0 || sheet != 0) {
        when { selectedPin != null -> selectedPin = null; creating -> creating = false
            managing -> managing = false; summaryWanted != null -> closeSummary()
            tab != 0 -> tab = 0; else -> sheet = 0 }
    }
    val density = LocalDensity.current
    val dm = context.resources.displayMetrics
    val physical = if(mobileLayout)PhysicalUiMetrics(48f,12f,12f,24f) else PhysicalUiMetrics.from(density.density, density.density * density.fontScale, dm.xdpi, dm.ydpi)
    CompositionLocalProvider(LocalPhysicalUi provides physical, LocalDrivingUx provides policy,LocalHandheldUi provides mobileLayout) {
    MaterialTheme(colorScheme = colors, typography = auroraTypography()) {
        V13Background(car=!mobileLayout) {
        Surface(color=if(dark)Color.Transparent else colors.background,contentColor=colors.onBackground,modifier=Modifier.fillMaxSize().testTag("app_root").semantics { stateDescription = if (historyReady) "기록 로드됨" else "기록 읽는 중" }) {
            if(carMode && carVehicle!=null) {
                if(showCarReview && !pinsReady) Box(Modifier.fillMaxSize()){AppText("Hearts 기록 확인 중 · 기존 파일 보존")}
                else if(showCarReview && carSummary!=null) CarV10Review(carSummary,catalog,
                    CarV10UiState(ux=if(heartsSafe)policy else DrivingUx(),dark=dark,
                        sourceDisclosure="주행 JSONL · "+if(carSummary.testData)"에뮬레이터/테스트 기록" else "실제 관측 기록"),
                    object:CarV10Actions {
                        override fun home(){closeSummary()}
                        override val remember:((String,List<String>,(CarV10SaveResult)->Unit)->Unit)={key,ids,done ->
                            scope.launch {
                                val result=withContext(Dispatchers.IO){heartsSaver.save(key,ids)}
                                refreshPins();done(result)
                            }
                        }
                    },initialExcluded=pins.firstOrNull { it.sourceDriveKey==carSummary.key && it.heartsReview!=null }
                        ?.heartsReview?.excludedMusicIds.orEmpty(),candidates=carSummary.tracks.mapNotNull { id -> carMusic?.observedCandidate(id)?.let { id to it } }.toMap(),
                        runtimeSavedMusicIds=pins.filter{it.sourceDriveKey==carSummary.key&&it.runtimeHeart!=null}.mapNotNull{it.track?.id}.toSet())
                else CarV10LivePlayer(player,carVehicle,policy,dark,carMusic,contextSnapshotProvider=contextSnapshotProvider,
                    onEndCollection=if(drive!=null)({summaryAutomatic=false;summaryWanted=drive;reviewStopRequested=true;onStopCollection();drive=driveProvider()}) else null){carMode=false}
            }
            else if(!(mobileLayout && carSummary?.endedAt!=null && carSummary.durationMs!=null && !creating && policy.complexAllowed))
            V13LiveScaffold(mobileLayout,tab,{tab=it;managing=false},containerColor = if(dark)Color.Transparent else colors.background, snackbarHost = { SnackbarHost(snackbar) { data ->
                    Surface(color = colors.surfaceVariant, shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(physical.gapDp.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppText(data.visuals.message, Modifier.weight(1f))
                            data.visuals.actionLabel?.let { label -> CarTextButton(onClick = data::performAction, enabled = policy.complexAllowed) { AppText(label) } }
                        }
                    }
                } },
                bottomBar = {
                    if(mobileLayout) V12BottomBar(tab){tab=it;managing=false} else
                    Surface(color = colors.surface) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = if(mobileLayout)8.dp else 24.dp, vertical = if(mobileLayout)4.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(physical.gapDp.dp)) {
                        tabs.forEachIndexed { i, name -> Surface(onClick = { tab = i; managing = false },
                            modifier = Modifier.weight(1f).heightIn(min = maxOf(if(mobileLayout)64f else 112f, physical.targetDp).dp).testTag("nav_$i")
                                .semantics { selected = tab == i }, shape = RoundedCornerShape(20.dp),
                            color = if (tab == i) (if(mobileLayout)colors.primaryContainer else colors.primary) else colors.surface,
                            contentColor = if (tab == i) (if(mobileLayout)colors.onPrimaryContainer else colors.onPrimary) else colors.onSurface) {
                            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                AuroraIcon(i, if (tab == i) (if(mobileLayout)colors.onPrimaryContainer else colors.onPrimary) else colors.onSurfaceVariant)
                                AppText(name, fontSize = if(mobileLayout)11.sp else 24.sp,maxLines=1)
                            }
                        } }
                    }
                    }
                }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    if (!mobileLayout) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AppText("gleo-type", fontWeight = FontWeight.Black, fontSize = if(mobileLayout)24.sp else 40.sp, color = colors.primary)
                        AppText("  /  ${tabs[tab]}", color = colors.onSurfaceVariant, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        carVehicle?.let { TextButton(onClick={carMode=true},modifier=Modifier.heightIn(min=64.dp).testTag("open_car_v10")){AppText("차량 플레이어")} }
                        AppText(if(mobileLayout) "로컬 기록" else if (drive != null) "● 수집 중" else "○ 수집 중지", color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    if (!mobileLayout) AppText(policy.label, Modifier.padding(horizontal = 24.dp).testTag("ux_status"), color = colors.onSurfaceVariant, fontSize = 24.sp)
                    if(mobileLayout && tab!=0 && tab!=3) V12Heading(listOf("홈","오늘의 사운드","캘린더","Hearts","마이")[tab],
                        when(tab){1->"지금의 분위기와 연결되는 한 곡";2->"이동 전 준비하고, 지난 음악을 다시 듣고";else->"나에게 맞춰 쌓이는 음악의 순간"})
                    history.error?.let { AppText(it, Modifier.padding(horizontal = 24.dp), color = colors.error) }
                    AnimatedContent(targetState = tab, label = "screen", modifier = Modifier.weight(1f).padding(bottom = physical.gapDp.dp), transitionSpec = {
                        if (policy.reduced) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    }) { page ->
                        tabStateHolder.SaveableStateProvider(page) {
                        when (page) {
                            0 -> HomeScreen(history, pins, state, drive, nowElapsed, sheet, { sheet = it },
                                { tab = 1 }, { selectedPin = it.id }, { openPin() },
                                { if (drive == null) { onStartCollection(); drive = driveProvider() }
                                  else { summaryAutomatic = false; summaryWanted = drive; onStopCollection(); drive = driveProvider() } }, player,
                                { summaryAutomatic = false; summaryWanted = it.key }, { tab = 3 })
                            1 -> MusicScreen(player, state, catalog, prefs, snackbar, feedback, drive, pins.mapNotNull { it.track?.id }.toSet(), recommendations, recommendationModelAdapter) { openPin() }
                            2 -> if (policy.reduced) RestrictionNotice("캘린더 상세", { tab = 1 }) else if (!historyReady) HistoryLoading() else CalendarScreen(history, pins, date, month, { date = it }, { month = it },
                                catalog, { selectedPin = it.id }, { summaryAutomatic = false; summaryWanted = it.key },planRepository)
                            3 -> if (policy.reduced) RestrictionNotice("Hearts 입력·관리", { tab = 1 }) else if(!pinsReady) HistoryLoading() else MobileV12Pins(history,pins,state,player,{selectedPin=it.id}) { PinsScreen(pins, { openPin() }, { selectedPin = it.id },deletedPins) { pin ->
                                scope.launch { runCatching {withContext(Dispatchers.IO){archiveRepository.restore(pin.id)}}
                                    .onSuccess {refreshPins()}.onFailure {error="복원에 실패했습니다 · 기존 기록 유지"} }
                            }
                            }
                            4 -> if (policy.reduced) RestrictionNotice("프로필·설정", { tab = 1 }) else if (!historyReady) HistoryLoading() else ProfileScreen(localId, history, pins.size, dark, { dark = it }, managing,
                                { managing = it }, onDiagnostics, context.filesDir,recommendationModelAdapter,onCalendar={tab=2}){tab=1}
                        }
                    }
                }
            }
            }
            pins.firstOrNull { it.id == selectedPin && policy.complexAllowed && photoEditPinId==null }?.let { pin ->
                CarDialog(onDismissRequest = { selectedPin = null }, title = { AppText("gleo Hearts · 저장한 순간") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
                        AppText(pin.track?.title ?: "음악 미선택", fontWeight = FontWeight.Bold)
                        AppText(pin.track?.artist ?: "")
                        AppText("기록: ${pin.occurredAt ?: pin.savedAt}")
                        AppText("저장: ${pin.savedAt}")
                        CarOutlinedButton(onClick={editPin(pin)},modifier=Modifier.testTag("edit_heart")){AppText("기록 수정")}
                        AppText(placeText(pin.place)); AppText("날씨: ${pin.weather ?: "미확인"}")
                        AppText(pin.tags.joinToString("  ") { "#$it" }); AppText(pin.note)
                        HeartLocalPhotos(pin)
                        if(mobileLayout)CarOutlinedButton(onClick={photoEditorToken=UUID.randomUUID().toString();photoEditPinId=pin.id},modifier=Modifier.testTag("v13_edit_photos")){AppText("사진·대표 이미지·메모")}

                        if (pin.attachments.isNotEmpty()) AppText("첨부: ${pin.attachments.joinToString { it.label }} · 업로드 미지원")
                        AppText(if (pin.driveId == null) "연결된 주행 없음" else "주행 기록 연결됨")
                        history.drives.firstOrNull { ArchiveProjection.linked(pin,it) }?.let { DriveDetails(it, catalog) }
                    } }, confirmButton = { CarTextButton(onClick = { selectedPin = null }) { AppText("닫기") } },
                    dismissButton = { CarTextButton(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { archiveRepository.delete(pin.id) } }
                                .onSuccess { selectedPin = null; refreshPins()
                                    if (snackbar.showSnackbar("저장 기록을 삭제했습니다", "복원") == SnackbarResult.ActionPerformed) {
                                        runCatching { withContext(Dispatchers.IO) { archiveRepository.restore(pin.id) } }
                                            .onFailure { error = "복원에 실패했습니다" }; refreshPins()
                                    }
                                }.onFailure { error = "삭제에 실패했습니다" }
                        }
                    }) { AppText("기록 삭제") } })
            }
            val summary = history.drives.firstOrNull { it.key == summaryWanted } ?: history.drives.firstOrNull { it.id == summaryWanted }
            if (!carMode && !mobileLayout && summary != null && !creating && policy.complexAllowed) {
                CarDialog(onDismissRequest = { closeSummary() }, title = { AppText(if (summary.endedAt == null) "진행 또는 종료 미확인" else "주행을 기록했어요") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
                        DriveDetails(summary, catalog)
                    } }, confirmButton = { CarButton(onClick = { openPin(summary) }) { AppText("이 주행 Hearts 저장") } },
                    dismissButton = { CarTextButton(onClick = { closeSummary() }) { AppText("닫기") } })
            }
            if(mobileLayout && summary!=null && summary.endedAt!=null && summary.durationMs!=null && !creating && policy.complexAllowed) {
                ReviewFinalTheme {
                V12SystemBars(true)
                ReviewFinalBackground {
                Scaffold(containerColor=Color.Transparent,bottomBar={V12BottomBar(tab,review=true){closeSummary();tab=it}}) { reviewPadding ->
                    Box(Modifier.padding(reviewPadding).consumeWindowInsets(reviewPadding)) {
                    if(summary.key.startsWith("sync/") && receivedInbox!=null) ReceivedReviewPanel(summary,receivedInbox,archiveRepository,{closeSummary()},{scope.launch{refreshPins()}})
                    else MobileV12Review(summary,catalog,pins.firstOrNull{it.sourceDriveKey==summary.key && it.heartsReview!=null}?.heartsReview?.excludedMusicIds.orEmpty(),{closeSummary()},
                        runtimeSavedMusicIds=pins.filter{it.sourceDriveKey==summary.key&&it.runtimeHeart!=null}.mapNotNull{it.track?.id}.toSet()) { ids,done ->
                        photoEditorToken=UUID.randomUUID().toString();photoReviewIds=ids
                        done(CarV10SaveResult(null,"사진을 추가하거나 건너뛰고 저장해 주세요"))
                    } }
                }
                }
                }
            }

            if(mobileLayout && policy.complexAllowed && (photoReviewIds!=null || photoEditPinId!=null)) {
                val edit=pins.singleOrNull{it.id==photoEditPinId}
                ArchivePhotoEditor(photoEditorToken,localPhotos,edit,
                    onCancel={photoReviewIds=null;photoEditPinId=null},
                    onSaved={photoReviewIds=null;photoEditPinId=null;selectedPin=null;closeSummary();tab=3;scope.launch{refreshPins()}},
                    onSave={media,done->scope.launch {
                        val ids=photoReviewIds
                        val result=withContext(Dispatchers.IO){
                            if(ids!=null && summary!=null)heartsSaver.save(summary.key,ids,media)
                            else runCatching {
                                check(policy.complexAllowed)
                                val current=archiveRepository.load().single{it.id==photoEditPinId}
                                archiveRepository.save(current.copy(attachments=(current.attachments+media.attachments).distinctBy{it.id},
                                    coverAttachmentId=media.coverAttachmentId,note=media.note))
                                CarV10SaveResult(current.id,"Hearts에 저장했습니다 · 로컬 저장")
                            }.getOrElse{CarV10SaveResult(null,"사진 저장 실패 · 기존 기록 유지")}
                        }
                        refreshPins();done(result)
                    }})
            }
            if (creating && policy.complexAllowed) {
                val selectedTrack = draftTrackId?.let { Track(it, draftTitle, draftArtist) }
                val place = validPlace(draftLatitude, draftLongitude)
                CreatePinDialog(selectedTrack, place, draftTags, draftNote, { draftTags = it }, { draftNote = it },
                    draftOptions.map { id -> catalog.firstOrNull { it.id == id } ?: Track(id, id, "메타데이터 미제공") },
                    { track -> draftTrackId = track.id; draftTitle = track.title; draftArtist = track.artist },
                    { if (!pinBusy) creating = false }, { tags, note ->
                        if (pinBusy || policy.reduced) return@CreatePinDialog
                        pinBusy = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) {
                                val source=draftDriveKey?.let { key -> history.drives.first { it.key==key } }
                                val existing=editingPinId?.let {id->archiveRepository.load().single {it.id==id}}
                                val pin=if(existing!=null)existing.copy(tags=tags,note=note)
                                    else if(source!=null)ArchiveProjection.pinFromDrive(source,selectedTrack,tags,note,draftAt)
                                    else Pin(draftId,draftAt,draftDrive,selectedTrack,place,tags,note)
                                archiveRepository.save(pin)
                            } }
                                .onSuccess { creating = false; closeSummary(); refreshPins() }
                                .onFailure { error = "저장에 실패했습니다. 다시 시도해 주세요." }
                            pinBusy = false
                            if (!creating) snackbar.showSnackbar("Hearts에 저장했습니다")
                        }
                    })
            }
            error?.takeIf { policy.complexAllowed }?.let { message -> CarDialog(onDismissRequest = { error = null }, title = { AppText("확인이 필요해요") },
                text = { AppText(message) }, confirmButton = { CarTextButton(onClick = { error = null }) { AppText("확인") } }) }
        }
    }
}
}
}

@Composable private fun RestrictionNotice(feature: String, onMusic: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
        AppText("$feature · 주차 후 이용", style = MaterialTheme.typography.headlineMedium)
        AppText("규제 상태를 확인할 수 없거나 제한 중입니다. 음악의 기본 재생 조작은 유지합니다.")
        if (onMusic != null) CarButton(onClick = onMusic) { AppText("음악으로 이동") }
    }
}

@Composable private fun TransportControls(player: PlaybackPort, state: PlaybackUi, prefix: String = "player") {
    Row(horizontalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp), verticalAlignment = Alignment.CenterVertically) {
        CarOutlinedButton(onClick = player::previous, enabled = state.connected && state.canPrevious, modifier = Modifier.testTag("${prefix}_previous").semantics { contentDescription = "이전 곡" }) { AppText("이전") }
        CarButton(onClick = player::toggle, enabled = state.connected && state.canPlay, modifier = Modifier.testTag("${prefix}_toggle")) {
            AppText(if (state.playing || state.wantsPlay) "일시정지" else "재생")
        }
        CarOutlinedButton(onClick = player::next, enabled = state.connected && state.canNext, modifier = Modifier.testTag("${prefix}_next").semantics { contentDescription = "다음 곡" }) { AppText("다음") }
    }
}

@Composable private fun HomeScreen(history: HistorySnapshot, pins: List<Pin>, state: PlaybackUi,
    drive: String?, now: Long, level: Int, onLevel: (Int) -> Unit, onMusic: () -> Unit,
    onPin: (Pin) -> Unit, onCreate: () -> Unit, onCollection: () -> Unit, player: PlaybackPort,
    onDrive: (DriveRecord) -> Unit, onArchive: () -> Unit) {
    if (LocalHandheldUi.current) {
        MobileArchiveHome(history, pins, state, level, onLevel, onMusic, onPin, onArchive, player,onReview=onDrive)
        return
    }
    val reduced = LocalDrivingUx.current.reduced
    val gap = LocalPhysicalUi.current.gapDp.dp
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val total = maxHeight
        val wide = maxWidth > 1100.dp
        val cs = MaterialTheme.colorScheme
        val speed = history.speedKmh.takeIf { drive != null && history.speedDrive == drive && history.speedElapsedMs?.let { now - it in 0..5000 } == true }
        Surface(Modifier.fillMaxSize(), color = cs.surfaceVariant, contentColor = cs.onSurface, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                val intro: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppText("YOUR DRIVE, YOUR SOUND", color = cs.primary, style = MaterialTheme.typography.labelMedium)
                        AppText("길 위의 순간,\n음악으로 남기다", style = MaterialTheme.typography.headlineLarge, maxLines = 2)
                        AppText(speed?.let { "%.0f km/h".format(it) } ?: "속도 미수신", fontSize = LocalPhysicalUi.current.primarySp.sp)
                        if (speed != null && history.speedIsTest) AppText("에뮬레이터 수신값", color = cs.onSurfaceVariant)
                        CarOutlinedButton(onClick = onCollection, modifier = Modifier.testTag("collection_toggle")) {
                            AppText(if (drive == null) "수집 시작" else "주행 수집 종료")
                        }
                        AppText("앱 수집 세션 · 시동 기준 아님", color = cs.onSurfaceVariant)
                        if (!reduced) AppText("음악 탭에서 모델·재생 가능 상태를 확인하세요 · 차량 정보가 없으면 취향 기반 fallback", color = cs.onSurfaceVariant)
                    }
                }
                val map: @Composable () -> Unit = {
                    Surface(color = cs.surface, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                AuroraIcon(3, cs.primary); AppText("지도 연결 준비 중", style = MaterialTheme.typography.titleLarge)
                            }
                            AppText("Hearts 아카이빙 공간 · 지도 SDK 미연결 · 길 안내 기능 아님", color = cs.onSurfaceVariant)
                            AppText("${pins.size}개 Hearts · 위치 있는 기록 ${pins.count{it.place!=null}}개 · 위치 미확인 ${pins.count{it.place==null}}개",Modifier.testTag("home_hearts_counts"))
                            val located = pins.filter { it.place != null }
                            if (located.isEmpty()) AppText("저장된 위치 없음 · 가상 위치를 만들지 않습니다", color = cs.onSurfaceVariant)
                            located.take(if (reduced) 0 else 2).forEach { pin ->
                                CarOutlinedButton(onClick = { onPin(pin) }, modifier = Modifier.testTag("map_pin_${pin.id}")) { AppText(pin.track?.title ?: "저장한 위치", maxLines = 1) }
                            }
                            CarTextButton(onClick = onArchive, enabled = !reduced) { AppText("Hearts 아카이브 열기 →") }
                        }
                    }
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) { Box(Modifier.weight(1f)) { intro() }; Box(Modifier.weight(1f)) { map() } }
                else if(LocalHandheldUi.current){if(!reduced)map()}
                else { intro(); if (!reduced) map() }
                Spacer(Modifier.height(total * .36f))
            }
        }
        var drag by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val currentLevel = if (reduced) 0 else level
        val base = maxOf(total * listOf(.34f, .62f, .93f)[currentLevel], minOf(320.dp, total * .8f))
        val height by animateDpAsState((base - with(density) { (if (reduced) 0f else drag).toDp() }).coerceIn(total * .25f, total * .96f),
            if (reduced) snap() else spring(), label = "sheet")
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height).testTag("home_sheet")
            .semantics { stateDescription = listOf("축소", "중간", "확장")[currentLevel] }, color = cs.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column {
                Column(Modifier.fillMaxWidth().heightIn(min = LocalPhysicalUi.current.targetDp.dp).testTag("sheet_handle")
                    .pointerInput(level, reduced) { if (!reduced) detectVerticalDragGestures(
                        onVerticalDrag = { change, amount -> change.consume(); drag += amount }, onDragCancel = { drag = 0f },
                        onDragEnd = { onLevel(GesturePolicy.sheetLevel(level, drag, 40.dp.toPx())); drag = 0f }) }
                    .clickable(enabled = !reduced) { onLevel((level + 1) % 3) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 12.dp).size(48.dp, 4.dp).clip(CircleShape).background(cs.onSurfaceVariant))
                    AppText(listOf("음악과 주행", "오늘의 기록", "주행 아카이브")[currentLevel], Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.padding(start = 32.dp, end = 32.dp, top = gap).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                    val song: @Composable () -> Unit = { Column {
                        AppText(state.track?.title ?: "음악을 선택해 주세요", fontSize = 36.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                        AppText(state.track?.artist ?: "로컬 테스트 라이브러리", color = cs.onSurfaceVariant, maxLines = 1)
                    } }
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(1f)) { song() }; TransportControls(player, state, "home_player")
                        CarTextButton(onClick = onMusic) { AppText("음악 열기 →") }
                    } else { song(); TransportControls(player, state, "home_player") }
                    if (!reduced && currentLevel > 0) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        CarTextButton(onClick = onCreate) { AppText("Hearts 저장") }
                        if (!wide) CarTextButton(onClick = onMusic) { AppText("음악 열기 →") }
                    }
                    if (currentLevel > 0 && !reduced) {
                        AppText("최근 Hearts", style=MaterialTheme.typography.titleLarge)
                        pins.sortedByDescending{it.occurredAt ?: it.savedAt}.take(3).forEach {pin->PinRow(pin){onPin(pin)}}
                        AppText("최근 앱 수집 기록", style = MaterialTheme.typography.titleLarge)
                        if (history.drives.isEmpty()) EmptyState("아직 주행 기록이 없어요", "수집된 기록이 여기에 쌓입니다")
                        history.drives.take(3).forEach { d ->
                            CarOutlinedButton(onClick = { onDrive(d) }) { AppText("${d.date ?: "날짜 미확인"} · ${d.tracks.size}곡 · 요약 열기") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable private fun MusicScreen(player: PlaybackPort, state: PlaybackUi, catalog: List<Track>,
    prefs: android.content.SharedPreferences, snackbar: SnackbarHostState, feedback: RecommendationFeedback,
    driveId: String?, savedIds: Set<String>, recommendationHistory: RecommendationHistory,
    modelAdapter:com.example.gleo_type.recommendation.RecommendationModelAdapter?=null,onPin: () -> Unit) {
    val reduced = LocalDrivingUx.current.reduced
    val gap = LocalPhysicalUi.current.gapDp.dp
    var excluded by remember { mutableStateOf(prefs.getStringSet("excluded", emptySet())!!.toSet()) }
    val scope = rememberCoroutineScope()
    fun record(action: String, id: String) {
        val at = Instant.now().toString()
        val elapsed = SystemClock.elapsedRealtime()
        feedback.submit(action, id, driveId, if (state.track?.id == id) state.playbackSessionId else null, at, elapsed) {
            scope.launch { snackbar.showSnackbar("추천 설정은 반영됐으나 피드백 파일 저장에 실패했습니다") }
        }
    }
    fun exclude(track: Track) {
        if (reduced) return
        excluded = excluded + track.id; prefs.edit().putStringSet("excluded", excluded).apply()
        record("recommendation_excluded", track.id)
        scope.launch { if (snackbar.showSnackbar("추천 목록에서 제외했습니다", "복원") == SnackbarResult.ActionPerformed) {
            excluded = excluded - track.id; prefs.edit().putStringSet("excluded", excluded).apply()
            record("recommendation_restored", track.id)
        } }
    }
    if(LocalHandheldUi.current) {
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(if(state.playing)"Media3 재생 중" else "Media3 · 재생 대기",Modifier.weight(1f),fontSize=11.sp,maxLines=2)
                Row {
                    IconButton(onClick=player::previous,enabled=state.connected && state.canPrevious,modifier=Modifier.testTag("player_previous")){Icon(androidx.compose.ui.res.painterResource(com.example.gleo_type.R.drawable.car_v10_previous),"이전 곡")}
                    IconButton(onClick=player::toggle,enabled=state.connected && state.canPlay,modifier=Modifier.testTag("player_toggle")){Icon(androidx.compose.ui.res.painterResource(if(state.playing)com.example.gleo_type.R.drawable.car_v10_pause else com.example.gleo_type.R.drawable.car_v10_play),if(state.playing)"일시정지" else "재생")}
                    IconButton(onClick=player::next,enabled=state.connected && state.canNext,modifier=Modifier.testTag("player_next")){Icon(androidx.compose.ui.res.painterResource(com.example.gleo_type.R.drawable.car_v10_next),"다음 곡")}
                }
            }
            RecommendationPanel(catalog,state,savedIds,excluded,recommendationHistory,driveId,player::play,
                ::exclude,
                {excluded.forEach{record("recommendation_restored",it)};excluded=emptySet();prefs.edit().remove("excluded").apply()},player::retryCatalog,modelAdapter,onPin,player)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        val wide = maxWidth >= 1200.dp
        val artHeight = if (reduced) 180.dp else (maxHeight * .24f).coerceIn(160.dp, 280.dp)
        val nowPlaying: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                AppText("NOW PLAYING", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Column {
                    AppText(state.track?.title ?: "음악을 선택해 주세요", fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = if (reduced) 1 else 2)
                    AppText(state.track?.artist ?: "아티스트 미제공", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 30.sp, maxLines = 1)
                    AppText(if (state.buffering) "버퍼링" else if (state.playing) "재생 중" else "일시정지 / 대기", color = MaterialTheme.colorScheme.primary)
                }
                TransportControls(player, state)
                AlbumCover(state, Modifier.fillMaxWidth().height(artHeight))
                if (!reduced) {
                    var seeking by remember(state.track?.id) { mutableStateOf<Float?>(null) }
                    val duration = state.duration
                    Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, (duration ?: 1).toFloat()),
                        onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { player.seek(it.toLong()) }; seeking = null },
                        enabled = state.connected && duration != null, valueRange = 0f..(duration ?: 1L).toFloat(),
                        colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = LocalPhysicalUi.current.targetDp.dp).testTag("playback_seek"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { AppText(clock(seeking?.toLong() ?: state.position)); AppText(duration?.let(::clock) ?: "길이 미확인") }
                    CarOutlinedButton(onClick = onPin) { AppText("현재 음악 Hearts 저장") }
                } else AppText("탐색·입력은 UX 제한 해제 후 이용", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
                AppText("${if (state.connected) "Media3 연결됨" else "서비스 연결 중"} · 로컬 테스트 음악", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
        val curated: @Composable () -> Unit = {
            RecommendationPanel(catalog, state, savedIds, excluded, recommendationHistory, driveId,
                player::play, ::exclude, {
                    excluded.forEach { record("recommendation_restored", it) }
                    excluded = emptySet(); prefs.edit().remove("excluded").apply()
                    scope.launch { snackbar.showSnackbar("제외한 추천을 복원했습니다") }
                }, player::retryCatalog,modelAdapter,playbackPort=player)
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) { Box(Modifier.weight(1f)) { nowPlaying() }; Box(Modifier.weight(1f)) { curated() } }
        else if (reduced) nowPlaying()
        else Column(Modifier.verticalScroll(rememberScrollState())) { Box(Modifier.height(if(LocalHandheldUi.current)700.dp else 1000.dp)) { nowPlaying() }; Box(Modifier.height(if(LocalHandheldUi.current)750.dp else 800.dp)) { curated() } }
    }
}

@Composable internal fun RecommendationCard(track: Track, onPlay: () -> Unit, onExclude: () -> Unit, pageOffset: Float, height: androidx.compose.ui.unit.Dp) {
    var drag by remember(track.id) { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(drag, spring(), label = "recommendation_return")
    val threshold = with(LocalDensity.current) { 108.dp.toPx() }
    val fraction = (-offset / threshold).coerceIn(0f, 1f)
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(24.dp))
        .background(Color(0xFFB52939).copy(alpha = .1f + fraction * .85f))) {
        AppText("추천에서 제외 ↑", Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White)
        Surface(Modifier.fillMaxSize().offset { IntOffset(0, offset.roundToInt()) }
            .graphicsLayer { scaleX = 1f - .04f * abs(pageOffset).coerceIn(0f, 1f); scaleY = scaleX }
            .testTag("recommendation_${track.id}").semantics { contentDescription = "추천 ${track.title}" }
            .pointerInput(track.id) {
                detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceIn(-size.height.toFloat(), 0f) },
                    onDragCancel = { drag = 0f }, onDragEnd = { if (GesturePolicy.dismiss(drag, threshold)) onExclude(); drag = 0f })
            }, shape = RoundedCornerShape(24.dp), color = Color.Transparent, contentColor = Color(MidnightAurora.text)) {
            Column(Modifier.background(Brush.linearGradient(listOf(Color(MidnightAurora.blue), Color(MidnightAurora.cardEnd)))).padding(32.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AppText("LOCAL / ${track.id.takeLast(4)}", color = Color(MidnightAurora.text), style = MaterialTheme.typography.labelMedium)
                    AuroraIcon(1, Color(MidnightAurora.text))
                }
                Column {
                    AppText(track.title, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AppText(track.artist, color = Color(MidnightAurora.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
                    CarButton(onClick = onPlay, modifier = Modifier.testTag("recommendation_play_${track.id}")) { AppText("재생") }
                    CarTextButton(onClick = onExclude, colors = ButtonDefaults.textButtonColors(contentColor = Color(MidnightAurora.text))) { AppText("추천 제외") }
                }
            }
        }
    }
}

@Composable private fun AlbumCover(state: PlaybackUi, modifier: Modifier) {
    val bitmap = remember(state.artwork) { state.artwork?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(MidnightAurora.surface), Color(MidnightAurora.background))))) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "현재 음악 앨범 이미지", Modifier.fillMaxSize())
        else {
            AppText("g", Modifier.align(Alignment.Center), fontSize = 96.sp, fontWeight = FontWeight.Black, color = Color(MidnightAurora.lavender))
            AppText("앨범 아트 미제공", Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color(MidnightAurora.secondary), fontSize = 11.sp)
        }
    }
}

@Composable private fun CalendarScreen(history: HistorySnapshot, pins: List<Pin>, selected: String, monthText: String,
    onDate: (String) -> Unit, onMonth: (String) -> Unit, catalog: List<Track>, onPin: (Pin) -> Unit, onDrive: (DriveRecord) -> Unit,plans:MusicPlanRepository?=null) {
    val month = runCatching { YearMonth.parse(monthText) }.getOrDefault(YearMonth.now())
    val date = runCatching { LocalDate.parse(selected) }.getOrDefault(LocalDate.now())
    var dayOnly by rememberSaveable { mutableStateOf(false) }
    var monthView by rememberSaveable { mutableStateOf(false) }
    if(LocalHandheldUi.current && !monthView) {
        MobileV12Calendar(history,pins,date,onDate,onDrive,onPin,{monthView=true}) {plans?.let{PlannedMusicPanel(date,catalog,it)}};return
    }
    val gap = LocalPhysicalUi.current.gapDp.dp
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val wide = maxWidth >= 1400.dp
        val calendar: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CarTextButton(onClick = { onMonth(month.minusMonths(1).toString()) }, modifier = Modifier.testTag("calendar_previous")) { AppText("← 이전 달") }
            AppText("${month.year}년 ${month.monthValue}월", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            CarTextButton(onClick = { onMonth(month.plusMonths(1).toString()) }, modifier = Modifier.testTag("calendar_next")) { AppText("다음 달 →") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = if(LocalHandheldUi.current)0.dp else LocalPhysicalUi.current.gapDp.dp
            val width = maxOf(maxWidth, LocalPhysicalUi.current.targetDp.dp * 7 + (if(LocalHandheldUi.current)0.dp else gap) * 6)
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    listOf("월", "화", "수", "목", "금", "토", "일").forEach { AppText(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
                (0 until cells step 7).forEach { start ->
                    Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(gap)) { (start until start + 7).forEach { index ->
                        val day = index - offset + 1
                        if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(LocalPhysicalUi.current.targetDp.dp))
                        else {
                            val d = month.atDay(day)
                            val dayRecords = ArchiveProjection.onDate(history,pins,d)
                            val recorded = dayRecords.first.isNotEmpty() || dayRecords.second.isNotEmpty()
                            CarTextButton(onClick = { onDate(d.toString()); dayOnly = true }, modifier = Modifier.weight(1f).testTag("calendar_day_$day"),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (date == d) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (date == d) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                                AppText("$day${if (recorded) " •" else ""}", fontSize = if(LocalHandheldUi.current)14.sp else 36.sp)
                            }
                        }
                    } }
                }
            }
        }

        } }
        val details: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            if (!wide) CarTextButton(onClick = { dayOnly = false }) { AppText("← 월간 달력") }
        AppText("${date.monthValue}월 ${date.dayOfMonth}일의 기록", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        val (daily,saved) = ArchiveProjection.onDate(history,pins,date)
        if(date>LocalDate.now() && plans!=null) PlannedMusicPanel(date,catalog,plans)
        if (daily.isEmpty() && saved.isEmpty()) EmptyState("이 날짜에는 기록이 없어요", "저장된 주행과 음악이 있는 날짜에 표시됩니다")
        daily.forEach { record -> Surface(onClick = { onDrive(record) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
                AppText(if (record.endedAt == null) "종료 미확인 주행" else "주행 요약", fontWeight = FontWeight.Bold)
                AppText("${record.tracks.size}곡 · ${record.durationMs?.let(::clock) ?: "종료 미확인"} · 상세 보기 →", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        saved.forEach { PinRow(it) { onPin(it) } }

        } }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.weight(.56f).verticalScroll(rememberScrollState())) { calendar() }
            Box(Modifier.weight(.44f).verticalScroll(rememberScrollState())) { details() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (dayOnly) details() else calendar()
        }
    }
}

@Composable private fun PinsScreen(pins: List<Pin>, onCreate: () -> Unit, onPin: (Pin) -> Unit,deletedPins:List<Pin> = emptyList(),onRestore:(Pin)->Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
        if(!LocalHandheldUi.current)AppText("Hearts 아카이브", fontSize = 29.sp, fontWeight = FontWeight.Bold)
        if(!LocalHandheldUi.current)AppText("나의 주행 아카이브", color = MaterialTheme.colorScheme.onSurfaceVariant)
        CarButton(onClick = onCreate, modifier = Modifier.heightIn(min = 56.dp).testTag("create_pin")) { AppText("+ 지금 이 순간 저장") }
        if (pins.isEmpty()) EmptyState("아직 저장한 순간이 없어요", "위치가 없어도 음악과 메모를 저장할 수 있어요")
        var filter by rememberSaveable {mutableStateOf("")}
        OutlinedTextField(value=filter,onValueChange={filter=it.take(120)},label={AppText("음악·날짜·태그 검색")},modifier=Modifier.fillMaxWidth().testTag("hearts_search"))
        val visiblePins=pins.sortedByDescending { it.occurredAt ?: it.savedAt }.filter {pin ->
            listOf(pin.track?.title,pin.track?.artist,pin.occurredAt ?: pin.savedAt,pin.tags.joinToString(" "),pin.note,placeText(pin.place))
                .any{it?.contains(filter,ignoreCase=true)==true}
        }
        if(LocalHandheldUi.current) {
            visiblePins.firstOrNull()?.let{pin->V13HeartCard(pin,onClick={onPin(pin)})}
            visiblePins.drop(1).chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                pair.forEach{pin->Box(Modifier.weight(1f)){V13HeartCard(pin,compact=true,onClick={onPin(pin)})}}
                if(pair.size==1)Spacer(Modifier.weight(1f))
            }}
        } else visiblePins.forEach {pin->PinRow(pin){onPin(pin)}}
        if(deletedPins.isNotEmpty()) {
            AppText("삭제한 Hearts · 복원 가능")
            deletedPins.forEach {pin -> CarTextButton(onClick={onRestore(pin)},modifier=Modifier.testTag("restore_heart_${pin.id}")){AppText("${pin.track?.title ?: "저장한 순간"} 복원")} }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun PinRow(pin: Pin, onClick: () -> Unit) {
    if(LocalHandheldUi.current){V13HeartCard(pin,onClick=onClick);return}
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AuroraIcon(3, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText(pin.track?.title ?: "저장한 순간", fontWeight = FontWeight.Bold)
                AppText("${dateOf(pin.occurredAt ?: pin.savedAt)} · ${placeText(pin.place)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppText(pin.tags.joinToString(" ") { "#$it" })
            }
            AppText("열기 →")
        }
    }
}

@Composable private fun CreatePinDialog(track: Track?, place: Place?, tags: String, note: String, onTags: (String) -> Unit, onNote: (String) -> Unit,
    options: List<Track>, onTrack: (Track) -> Unit, onCancel: () -> Unit, onSave: (List<String>, String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    CarDialog(onDismissRequest = onCancel, title = { AppText("gleo Hearts에 저장") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
            AppText(track?.let { "${it.title} · ${it.artist}" } ?: "선택한 음악 없음")
            if (options.size > 1) Box {
                CarOutlinedButton(onClick = { choosing = true }) { AppText("이 주행의 다른 음악 선택") }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    options.forEach { option ->
                        CarTextButton(onClick = { onTrack(option); choosing = false }, modifier = Modifier.padding(vertical = LocalPhysicalUi.current.gapDp.dp / 2, horizontal = 16.dp)) { AppText(option.title, maxLines = 1) }
                    }
                }
            }
            AppText(placeText(place)); AppText("날씨 미확인", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppText("날짜·음악·위치·메모를 기기에 저장합니다. 사진 첨부·업로드는 미지원입니다.")
            OutlinedTextField(value = tags, onValueChange = { onTags(it.take(120)) }, label = { AppText("태그 · 쉼표로 구분") }, modifier = Modifier.fillMaxWidth().heightIn(min = LocalPhysicalUi.current.targetDp.dp).testTag("pin_tags"))
            OutlinedTextField(value = note, onValueChange = { onNote(it.take(1000)) }, label = { AppText("나에게 남기는 메모") }, modifier = Modifier.fillMaxWidth().heightIn(min = LocalPhysicalUi.current.targetDp.dp).testTag("pin_note"))
        }
    }, confirmButton = { CarButton(onClick = { onSave(tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().take(10), note) }, modifier = Modifier.testTag("confirm_pin")) { AppText("저장") } },
        dismissButton = { CarTextButton(onClick = onCancel) { AppText("취소") } })
}

@Composable private fun ProfileScreen(localId: String, history: HistorySnapshot, pinCount: Int, dark: Boolean,
    onDark: (Boolean) -> Unit, managing: Boolean, onManaging: (Boolean) -> Unit, onDiagnostics: () -> Unit, filesDir: File,model:com.example.gleo_type.recommendation.RecommendationModelAdapter?=null,onCalendar:()->Unit={},onPreferences:()->Unit={}) {
    if(LocalHandheldUi.current) {
        MobileV12Profile(localId,history,pinCount,dark,onDark,managing,onManaging,onPreferences,onCalendar,onDiagnostics);return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
        AppText(if (managing) "데이터 관리" else "나만의 리듬", fontSize = 44.sp, fontWeight = FontWeight.Bold)
        BadgeText("로컬 프로필 · 계정 미연결")
        SelectionContainer { AppText("${localId.take(14)}…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AppText("기기 보관 기록 · 사용자 미확인 과거 기록 포함", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) {
            Stat("${history.drives.size}", "앱 수집 세션")
            Stat("$pinCount", "저장한 순간")
            Stat(if (history.listeningAvailable) clock(history.listeningWallMs.toLong()) else "—", "관측 청취 시간")
        }
        var modelState by remember(model){mutableStateOf<com.example.gleo_type.recommendation.ModelAvailability?>(null)}
        LaunchedEffect(model){modelState=withContext(Dispatchers.IO){model?.availability(emptySet())}}
        AppText(when {modelState?.loaded!=true->"모델 미설치 또는 미확인 · 취향 상태 미확인";modelState?.hasPreference==true->"로컬 취향 벡터 저장됨";else->"초기 취향 설정 전"},Modifier.testTag("profile_preference_status"))
        AppText("로그인·기기 간 동기화 미지원 · 이 기기의 기록입니다")
        CarOutlinedButton(onClick=onPreferences,modifier=Modifier.testTag("profile_music_preferences")){AppText("음악 취향 관리") }
        AppText("테스트 재생·탐색을 선호도나 거부 반응으로 단정하지 않습니다. 청취 시간은 관측 하한입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText("다크모드", Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = onDark, modifier = Modifier.sizeIn(minWidth = LocalPhysicalUi.current.targetDp.dp, minHeight = LocalPhysicalUi.current.targetDp.dp).testTag("dark_mode"))
        }
        CarOutlinedButton(onClick = { onManaging(!managing) }, modifier = Modifier.testTag("data_management")) { AppText(if (managing) "프로필로 돌아가기" else "설정 · 데이터 관리") }
        if (managing) {
            AppText("앱 내부 UTF-8 JSONL과 로컬 gleo Hearts를 사용합니다. 음악 파일은 삭제하지 않습니다.")
            SelectionContainer { AppText("${filesDir.absolutePath}/events/\n${filesDir.absolutePath}/archive/pins.json\n${filesDir.absolutePath}/feedback/\n${filesDir.absolutePath}/recommendations/", fontSize = 24.sp) }
            AppText("PC 추출: export-events.ps1 / export-archive.ps1 / export-feedback.ps1 / export-recommendations.ps1", fontSize = 24.sp)
            AppText("읽기 실패 행: ${history.malformedLines} · 계정/지도/날씨/Gleo/추천 서버 미연결")
            if (excludedCount(LocalContext.current) > 0) AppText("추천 제외 목록은 음악 화면에서 복원할 수 있습니다")
            CarTextButton(onClick = onDiagnostics) { AppText("기존 SDK 진단 화면 열기") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun DriveDetails(record: DriveRecord, catalog: List<Track>) {
    AppText("${record.date ?: "날짜 미확인"} · ${record.startedAt?.let { runCatching { Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString() }.getOrNull() } ?: "시각 미확인"}")
    AppText("총 수집 시간: ${record.durationMs?.let(::clock) ?: "종료 시각 미확인"} · 주행 거리 미수집")
    AppText("청취: ${if (record.hasListening) clock(record.listeningWallMs.toLong()) + " (관측 하한)" else "구간 데이터 없음"}")
    AppText(record.tracks.joinToString(" · ") { id -> catalog.firstOrNull { it.id == id }?.title ?: id }.ifEmpty { "재생 기록 없음" })
    record.playbacks.forEach { p -> AppText("${p.musicId ?: "음악 미확인"}: ${clock(p.creditedWallMs.toLong())} · ${p.confidence ?: "요약 미수신"}") }
    if(record.qualityFlags.isNotEmpty())AppText("기록 품질: ${record.qualityFlags.joinToString()}")
    AppText(placeText(record.place), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (record.testData) AppText("에뮬레이터 / 테스트 수집 기록", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
}
@Composable private fun BadgeText(text: String) { AppText(text, Modifier.clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium) }
@Composable private fun HistoryLoading() { Box(Modifier.fillMaxSize().padding(32.dp)) { AppText("기록을 불러오는 중 · 아직 통계를 표시하지 않습니다", Modifier.testTag("history_loading")) } }
@Composable private fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(LocalPhysicalUi.current.gapDp.dp)) { AppText(title, style = MaterialTheme.typography.titleMedium); AppText(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Stat(value: String, title: String) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { AppText(value, fontSize = 26.sp, fontWeight = FontWeight.Bold); AppText(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
private fun clock(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun placeText(place: Place?) = place?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "위치 미확인"
private fun excludedCount(context: Context) = context.getSharedPreferences("gleo_ui", Context.MODE_PRIVATE).getStringSet("excluded", emptySet())?.size ?: 0
@Composable private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
