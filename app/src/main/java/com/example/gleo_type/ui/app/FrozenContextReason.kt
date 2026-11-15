package com.example.gleo_type.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.json.JSONObject

/** Reads only persisted recommendation-time evidence. Never queries current vehicle/clock/weather. */
@Composable internal fun FrozenContextReason(raw:String) {
 val evidence=runCatching{JSONObject(raw).also{j->
  require(j.getInt("version")==1)
  val s=j.getJSONObject("snapshot");s.getString("createdAt");s.getString("contextSnapshotId")
  listOf("currentLocation","destination","driving","road","timeWeather","voice").forEach{key->
   val f=s.getJSONObject(key);f.keys().forEach{f.getJSONObject(it).getString("freshness")}}
  val d=j.getJSONObject("decision");d.getString("textStatus");d.getJSONArray("retrievedCaseIds")
  val scores=d.getJSONArray("scores");for(i in 0 until scores.length()){val r=scores.getJSONObject(i);r.getString("musicId");require(r.getDouble("total").isFinite());val p=r.getJSONObject("contributions");p.keys().forEach{require(p.getDouble(it).isFinite())}}
 }}.getOrNull()
 Column(Modifier.testTag("frozen_context_evidence")) {
  AppText("추천 생성 당시의 근거 · 현재 맥락 재조회 아님")
  if(evidence==null)AppText("저장된 근거를 읽을 수 없습니다") else {
   val s=evidence.getJSONObject("snapshot");val d=evidence.getJSONObject("decision")
   AppText("생성: ${s.getString("createdAt")} · ${s.getString("contextSnapshotId")}")
   listOf("currentLocation" to "위치","destination" to "목적지","driving" to "운전","road" to "도로","timeWeather" to "시간·날씨","voice" to "요청").forEach{(key,label)->
    val fields=s.getJSONObject(key);val states=fields.keys().asSequence().map{fields.getJSONObject(it).getString("freshness")}.groupingBy{it}.eachCount()
    AppText("$label: ${states.entries.joinToString{ "${when(it.key){"FRESH"->"신선";"STALE"->"오래됨";"MISSING"->"미수신";else->"사용 불가"}} ${it.value}" }}")
   }
   AppText("텍스트: ${d.getString("textStatus")} · 사용 사례 ${d.getJSONArray("retrievedCaseIds").length()}개")
   val scores=d.getJSONArray("scores")
   for(i in 0 until scores.length().coerceAtMost(10)){val row=scores.getJSONObject(i);val parts=row.getJSONObject("contributions")
    AppText("${row.getString("musicId")} · 최종 ${"%.3f".format(java.util.Locale.ROOT,row.getDouble("total"))}\n"+
     parts.keys().asSequence().filter{parts.getDouble(it)!=0.0}.sorted().joinToString{ "${mapOf("profile" to "개인 취향","voice" to "요청","cbr" to "유사 경험","driving" to "속도","time" to "시간","weather" to "날씨")[it] ?: it} ${"%.3f".format(java.util.Locale.ROOT,parts.getDouble(it))}" })
   }
   AppText("군집은 후보 분석용이며 점수 보정에 사용하지 않습니다")
  }
 }
}
