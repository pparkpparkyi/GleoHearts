package com.example.gleo_type.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import com.example.gleo_type.GleoApplication
import com.example.gleo_type.recommendation.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The current adapter only rearranges a disclosed local test catalog. It never commands Player. */
@Composable internal fun RecommendationPanel(catalog: List<Track>, state: PlaybackUi, savedIds: Set<String>,
    excluded: Set<String>, history: RecommendationHistory, driveId: String?, onPlay: (Track) -> Unit,
    onExclude: (Track) -> Unit, onRestore: () -> Unit, retryCatalog: () -> Unit,
    modelAdapter: RecommendationModelAdapter? = null,onArchive:(()->Unit)?=null,playbackPort:PlaybackPort?=null) {
    val scope = rememberCoroutineScope()
    val gap = LocalPhysicalUi.current.gapDp.dp
    val reduced = LocalDrivingUx.current.reduced
    val context = LocalContext.current
    val app = context.applicationContext as? GleoApplication
    val adapter = modelAdapter ?: remember { RecommendationModelAdapter(context.filesDir, app?.container?.localIdentity?.id ?: "unavailable",
        PlaybackMappingVerifier(context.applicationContext)::verify,
        drivingContext = { drive -> app?.container?.drivingContext?.current(drive) },
        drivingPolicy = runCatching { DrivingScorePolicy(context.getSharedPreferences("gleo_experiments",android.content.Context.MODE_PRIVATE)
            .getString("driving_recommendation_weight","0.05")!!.toDouble()) }.getOrDefault(DrivingScorePolicy())) }
    var modelStatus by remember { mutableStateOf(ModelAvailability(false,false,0,"모델 확인 중")) }
    var modelChecked by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf<String?>(null) }
    var genreMenu by remember { mutableStateOf(false) }
    var moodMenu by remember { mutableStateOf(false) }
    LaunchedEffect(catalog) {
        modelStatus = withContext(Dispatchers.IO) { adapter.availability(catalog.map { it.id }.toSet()) }
        modelChecked = true
    }
    var runs by remember { mutableStateOf<List<RecommendationRun>>(emptyList()) }
    var current by remember { mutableStateOf<RecommendationRun?>(null) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(MusicScope.THREE) }
    var message by remember { mutableStateOf<String?>(null) }
    fun play(track:Track) { val run=current; if(run!=null && playbackPort!=null)playbackPort.playRecommended(track,run) else onPlay(track) }
    fun build(selected: MusicScope, variant: Int, forceTest: Boolean = false) {
        if (busy || reduced) return
        busy = true; problem = null
        scope.launch {
        val result = runCatching { withContext(Dispatchers.IO) {
            if(forceTest) LocalRecommendationBuilder.build(catalog,selected,savedIds,excluded,variant,state.catalogObservedAt,driveId)
            else adapter.recommend(catalog, selected, savedIds, excluded, variant,state.catalogObservedAt, driveId)
        } }
        result.onFailure { problem = "목록 구성에 실패했습니다. 이전 결과는 보존됩니다"; busy = false }
        result.onSuccess { run -> history.submit(run) { failure -> scope.launch {
            busy = false
            if (failure != null) problem = "추천 기록 저장 실패 · 이전 결과를 보존했습니다"
            else { runs = runs + run; current = run; selection = selected; message =
                if(run.origin==RecommendationOrigin.AI_MODEL) if(run.contextEvidence!=null) "CLAP 취향·추천 당시 맥락 기여 기록 · 미수신 항목 제외 · 현재 재생 유지" else if(run.drivingDecision?.applied==true) "관측 차량 맥락의 실험 보정 적용 · 현재 재생 유지" else "현재 차량 정보 없이 취향 기반으로 추천 · 현재 재생 유지"
                else "테스트 목록을 구성했습니다 · 현재 재생은 유지됩니다" }
        } } }
        }
    }
    LaunchedEffect(history) {
        runCatching { withContext(Dispatchers.IO) { history.load() } }
            .onSuccess { runs = it; current = it.lastOrNull(); selection = current?.scope ?: MusicScope.THREE }
            .onFailure { problem = "추천 기록 읽기 실패 · 원본 파일을 보존했습니다" }
        ready = true
    }
    val aiUsable = modelStatus.loaded && modelStatus.hasPreference && modelStatus.playableCount > 0
    LaunchedEffect(ready, modelChecked, state.catalogReady, catalog, reduced) {
        if (modelChecked && current?.origin==RecommendationOrigin.AI_MODEL && !aiUsable) current=null
        if (ready && modelChecked && state.catalogReady && state.catalogError == null && catalog.isNotEmpty() && current == null && problem == null && !reduced)
            build(MusicScope.THREE, 0)
    }
    val tracks = current?.takeIf { it.origin!=RecommendationOrigin.AI_MODEL || aiUsable }?.musicIds.orEmpty()
        .mapNotNull { id -> catalog.firstOrNull { it.id == id && id !in excluded } }
    val phase = when {
        state.catalogError != null || problem != null -> RecommendationPhase.ERROR
        !ready || !modelChecked || !state.catalogReady || busy -> RecommendationPhase.LOADING
        tracks.isEmpty() -> RecommendationPhase.EMPTY
        else -> RecommendationPhase.READY
    }
    val pager = rememberPagerState(pageCount = { tracks.size })
    val renderBody: @Composable () -> Unit = {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
        if(!LocalHandheldUi.current)AppText("음악 제안", style = MaterialTheme.typography.headlineMedium)
        if(!LocalHandheldUi.current || androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp>=500)AppText(if(current?.origin==RecommendationOrigin.AI_MODEL && aiUsable) "AI CURATED · Gleo 미연결"
            else "테스트 목록 · AI 추천 미사용 / Gleo 미연결", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(!LocalHandheldUi.current)AppText(modelStatus.message, Modifier.testTag("clap_model_status"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(current?.origin==RecommendationOrigin.AI_MODEL) AppText(
            if(current?.contextEvidence!=null) "추천 당시 맥락·CBR의 준비된 항목만 적용 · 미학습 실험 설정"
            else if(current?.drivingDecision?.applied==true) "관측 차량 맥락 보정 · 실험 규칙 (학습된 적합도 아님)"
            else "현재 차량 정보 없이 취향 기반으로 추천", Modifier.testTag("driving_recommendation_status"))
        if (reduced) AppText("추천 탐색·구성은 UX 제한 해제 후 이용")
        else {
            if(modelChecked && modelStatus.loaded && !modelStatus.hasPreference) {
                AppText("초기 취향 선택 · 장르 0.6 / 분위기 0.4 (실험 설정)")
                Row(horizontalArrangement=Arrangement.spacedBy(gap)) {
                    Box { CarOutlinedButton(onClick={genreMenu=true}) { AppText(genre ?: "장르 선택") }
                        DropdownMenu(expanded=genreMenu,onDismissRequest={genreMenu=false},modifier=Modifier.heightIn(max=320.dp)) {
                            modelStatus.genres.forEach { item -> DropdownMenuItem(text={AppText(item)},onClick={genre=item;genreMenu=false}) }
                        }
                    }
                    Box { CarOutlinedButton(onClick={moodMenu=true}) { AppText(mood ?: "분위기 선택") }
                        DropdownMenu(expanded=moodMenu,onDismissRequest={moodMenu=false},modifier=Modifier.heightIn(max=320.dp)) {
                            modelStatus.moods.forEach { item -> DropdownMenuItem(text={AppText(item)},onClick={mood=item;moodMenu=false}) }
                        }
                    }
                }
                CarButton(enabled=!busy && (genre!=null || mood!=null),onClick={
                    busy=true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            adapter.initialize(setOfNotNull(genre),setOfNotNull(mood))
                            adapter.availability(catalog.map { it.id }.toSet())
                        } }.onSuccess { modelStatus=it; busy=false; build(selection,0) }
                            .onFailure { problem="취향 저장 실패 · 기존 기록 보존";busy=false }
                    }
                }) { AppText("선택 취향 저장") }
            }
            if(!LocalHandheldUi.current)Row(horizontalArrangement = Arrangement.spacedBy(gap)) { MusicScope.entries.forEach { item ->
                CarOutlinedButton(onClick = { build(item, 0) }, enabled = ready && modelChecked && state.catalogReady && !busy && state.catalogError == null,
                    modifier = Modifier.weight(1f).testTag("suggestion_${item.name}")) { AppText(item.label) }
            } }
            when (phase) {
                RecommendationPhase.LOADING -> AppText("로컬 음악 목록 준비 중", Modifier.testTag("recommendations_loading"))
                RecommendationPhase.ERROR -> Column {
                    AppText(problem ?: state.catalogError.orEmpty(), Modifier.testTag("recommendations_error"), color = MaterialTheme.colorScheme.error)
                    CarTextButton(onClick = { if (state.catalogError != null) retryCatalog() else build(selection, current?.variant ?: 0) }) { AppText("다시 시도") }
                }
                RecommendationPhase.EMPTY -> AppText(if (catalog.isEmpty()) "사용 가능한 음악이 없습니다"
                    else if(current?.origin==RecommendationOrigin.AI_MODEL) "검증 재생 후보 부족 또는 기청취·제외 필터로 남은 추천 없음"
                    else "선택한 목록에 음악이 없습니다 · 다른 범위 선택 또는 제외 복원", Modifier.testTag("recommendations_empty"))
                RecommendationPhase.READY -> Unit
            }
            if(current?.origin==RecommendationOrigin.AI_MODEL && tracks.isEmpty())
                CarTextButton(onClick={build(selection,0,true)},enabled=!busy,
                    modifier=Modifier.testTag("fallback_test_catalog")) { AppText("테스트 음악 목록 보기 · AI 추천 아님") }
            // A failed regeneration leaves the last verified result available.
            if (tracks.isNotEmpty()) {
                HorizontalPager(pager, key = { tracks[it].id }, pageSpacing = gap,
                    modifier = Modifier.fillMaxWidth().testTag("recommendation_pager").semantics { stateDescription = "${pager.currentPage + 1}/${tracks.size}" }) { page ->
                    if(LocalHandheldUi.current) Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { V12MusicCard(tracks[page],state,{play(tracks[page])},{onArchive?.invoke()},
                        if(current?.origin==RecommendationOrigin.AI_MODEL) {
                            val score=current?.scores?.get(tracks[page].id)
                            "CLAP 개인 취향 · ${if(current?.contextEvidence!=null)"추천 당시 맥락·기여 기록" else if(current?.drivingDecision?.applied==true)"관측 속도 보정 적용" else "차량 맥락 미사용"} · 점수 ${score?.let{"%.3f".format(it)} ?: "미제공"}"
                        } else "로컬 테스트 목록 · AI 추천·음성 분석 결과 아님",showActions=false)
                        TextButton(onClick={onExclude(tracks[page])}){Text("이 추천 제외")}
                    }
                    else RecommendationCard(tracks[page], { play(tracks[page]) }, { onExclude(tracks[page]) },
                        (pager.currentPage - page) + pager.currentPageOffsetFraction, 360.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    CarTextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) { AppText("이전") }
                    AppText("${pager.currentPage + 1} / ${tracks.size}")
                    CarTextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(tracks.lastIndex)) } }) { AppText("다음") }
                }
                AppText(if(LocalHandheldUi.current)"좌우 탐색 · 제외 후 복원 가능" else "좌우 탐색 · 위로 밀어 추천 제외")
            }
            if(LocalHandheldUi.current)Row(horizontalArrangement=Arrangement.spacedBy(gap)) {MusicScope.entries.forEach { item ->
                CarOutlinedButton(onClick={build(item,0)},enabled=ready && modelChecked && state.catalogReady && !busy && state.catalogError==null,modifier=Modifier.weight(1f).testTag("suggestion_${item.name}")){AppText(item.label)}
            }}
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                CarButton(onClick = { build(selection, (current?.variant ?: 0) + 1) }, enabled = ready && modelChecked && !busy && state.catalogReady && state.catalogError == null && catalog.isNotEmpty(),
                    modifier = Modifier.testTag("regenerate_recommendations")) { AppText("목록 다시 구성") }
                CarTextButton(onClick = { details = true }, enabled = current != null, modifier = Modifier.testTag("recommendation_sources")) { AppText("출처·근거") }
            }
            AppText(if(current?.origin==RecommendationOrigin.AI_MODEL && aiUsable) "모델: ${current?.modelVersion} · 목록 계산과 곡 스킵은 별개"
                else "테스트 순서만 변경 · 곡 스킵과 별개", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(aiUsable) CarTextButton(enabled=state.connected && state.error==null && state.playbackSessionId!=null && driveId!=null && state.track?.id in modelStatus.playableIds,onClick={
                val id=state.track?.id; val pb=state.playbackSessionId; val drive=driveId
                if(id!=null && pb!=null && drive!=null) scope.launch {
                    val accepted=runCatching { withContext(Dispatchers.IO) { playbackPort?.likeObserved(adapter,id,pb,drive) ?: false } }.getOrDefault(false)
                    message=if(accepted) "좋아요 저장 · 실제 재생에 연결된 CBR 반영 · 취향은 유효 청취 요약 확인 후 학습" else "추천과 실제 재생 연결을 확인할 수 없어 학습하지 않았습니다"
                }
            },modifier=Modifier.testTag("recommendation_like")) { AppText("현재 곡 좋아요") }
            message?.let { AppText(it, Modifier.testTag("recommendation_feedback")) }
            if (runs.size > 1) CarTextButton(onClick = { past = true }, modifier = Modifier.testTag("recommendation_history")) { AppText("이전 목록 (${runs.size})") }
            if (excluded.isNotEmpty()) CarTextButton(onClick = onRestore, modifier = Modifier.testTag("restore_recommendations")) { AppText("제외한 추천 전체 복원 (${excluded.size})") }
        }
        Spacer(Modifier.height(gap))
    }
    }
    if(LocalHandheldUi.current) Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)){renderBody()}
        tracks.getOrNull(pager.currentPage)?.takeIf{!reduced}?.let{track ->
            Surface(color=MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().padding(vertical=10.dp)) {V12MusicActions(track,state,{play(track)},{onArchive?.invoke()})}
            }
        }
    } else renderBody()
    if (details && !reduced) CarDialog(onDismissRequest = { details = false }, title = { AppText("실제 사용한 데이터") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
            AppText(if(current?.origin==RecommendationOrigin.AI_MODEL && aiUsable) if(current?.contextEvidence!=null) "CLAP 개인 취향 + 추천 당시 맥락·CBR의 항목별 기여 (미학습 실험 설정)" else if(current?.drivingDecision?.applied==true) "CLAP 취향 + 관측 차량 맥락 · 실험적 태그 보정" else "CLAP 코사인 유사도 · 개인 취향 벡터 · 차량 맥락 미사용"
                else "AI 설명이 아닙니다 · 차량 맥락·음향 특징 미사용")
            current?.let { run ->
                AppText("출처: ${run.origin} · ${run.algorithm}")
                run.drivingDecision?.let { d ->
                    AppText("차량 품질: ${d.input?.quality ?: "unknown"} · 적용: ${d.applied} · fallback: ${d.fallbackReason ?: "없음"}")
                    AppText("규칙: ${d.policyVersion} · 가중치: ${d.weight} (미학습 실험값)")
                    d.input?.let { input -> AppText("관측 상태: ${input.motionState ?: "unknown"} · 속도: ${input.averageSpeedKmh ?: "미수신"} · coverage: ${input.coverage} · 출처: ${input.source}") }
                    AppText("목록 생성 시점의 관측이며, 재계산 시 갱신됩니다.")
                    d.scores.forEach { s -> AppText("${s.musicId}: 취향 ${s.preferenceScore}, 맥락 보정 ${s.contextCorrection}, 최종 ${s.finalScore} · 일치한 카탈로그 태그: ${s.matchingCatalogTags.sorted()}") }
                }
                run.modelVersion?.let { AppText("모델: $it · 점수: ${run.scores}") }
                run.contextEvidence?.let { FrozenContextReason(it) }
                AppText("목록 수신: ${run.catalogObservedAt ?: "시각 미제공"}\n구성: ${run.createdAt}")
                run.usedFeatures.forEach { AppText("${it.name}\n${it.source} · ${it.observedAt ?: "시각 미제공"}") }
            }
        }
    }, confirmButton = { CarTextButton(onClick = { details = false }) { AppText("닫기") } })
    if (past && !reduced) CarDialog(onDismissRequest = { past = false }, title = { AppText("이전 테스트 목록") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) { runs.asReversed().forEach { run ->
            CarOutlinedButton(onClick = { current = run; selection = run.scope; past = false; message = "이전 목록을 복원했습니다 · 현재 제외 설정은 유지됩니다" },
                modifier = Modifier.testTag("restore_run_${run.id}")) { AppText("${run.scope.label} · ${run.createdAt} · ${run.musicIds.size}곡") }
        } }
    }, confirmButton = { CarTextButton(onClick = { past = false }) { AppText("닫기") } })
}
