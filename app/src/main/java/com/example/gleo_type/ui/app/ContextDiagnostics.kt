package com.example.gleo_type.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.gleo_type.context.*

/** Privacy-first debug projection. No coordinates, destination names, IDs or utterance text. */
@Composable fun ContextDiagnostics(snapshot:ContextSnapshot?,run:RecommendationRun?,modifier:Modifier=Modifier) {
 Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("6대 맥락 · 추천 당시 근거",style=MaterialTheme.typography.titleLarge)
  Text("개발 진단 · 좌표/장소명/사용자 식별자/발화 원문 비공개")
  val groups=listOf("위치" to snapshot?.currentLocation,"목적지" to snapshot?.destination,"운전 행동" to snapshot?.driving,
   "도로" to snapshot?.road,"시간·날씨" to snapshot?.timeWeather,"사용자 발화" to snapshot?.voice)
  groups.forEachIndexed{i,(title,fields)->OutlinedCard(Modifier.fillMaxWidth().testTag("context_group_$i")) {
   Column(Modifier.padding(12.dp)) {
    Text(title,style=MaterialTheme.typography.titleMedium)
    fields.orEmpty().forEach{(key,f)->
     val age=f.observedElapsedMs?.let{at->snapshot!!.capturedElapsedMs-at}?.takeIf{it>=0}
     Text("$key: ${f.freshness} · ${if(f.value==null)"값 없음" else "수신값 존재"} · ${f.origin}")
     Text("출처: ${f.source} · 관측 나이 ${age?.let{"${it}ms"} ?: "미확인"}",style=MaterialTheme.typography.bodySmall)
    }
    if(fields.isNullOrEmpty())Text("미수신 · 값 생성 안함")
   }
  }}
  val decision=run?.fusionDecision
  Text("실제 점수 기여 · 첫 추천 후보 기준",style=MaterialTheme.typography.titleMedium)
  if(decision==null)Text("미제공 · 모델/취향/실제 추천 준비 여부 확인")
  else {
   val first=decision.scores.firstOrNull()
   first?.contributions?.forEach{(name,value)->
    Text("$name: %.5f".format(value),Modifier.testTag("contribution_$name"))
    LinearProgressIndicator(progress={kotlin.math.abs(value).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
   }
   Text("막대는 절대 기여 크기, 음수 부호는 위 수치에 표시 · 성능 지표 아님")
   Text("CBR 사용 사례 ${decision.retrievedCaseIds.size}개 · ${decision.textStatus}")
   Text("위치/목적지/도로는 관측 feature가 있는 CBR 경로만 사용 · 존재 자체가 직접 가산은 아님")
  }
  Text("K-Means 8 / GMM 9: 진단용 군집 · 운영 순위 가산 없음")
  Text("날씨 공급자/공식 음성/실차 미검증 항목을 분석 성공으로 표시하지 않습니다")
 }
}
