package com.example.gleo_type.ui.app

import androidx.compose.runtime.*
import com.example.gleo_type.sync.DurableReviewInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same Review, archive and selection semantics. Incoming media is metadata, not playable audio. */
@Composable internal fun ReceivedReviewPanel(record:DriveRecord,inbox:DurableReviewInbox,archives:ArchiveRepository,
    close:()->Unit,saved:()->Unit) {
    val scope=rememberCoroutineScope();val id=record.key.removePrefix("sync/")
    var data by remember(id){mutableStateOf<Pair<List<Track>,List<String>>?>(null)}
    var failed by remember(id){mutableStateOf(false)}
    LaunchedEffect(id) {runCatching{withContext(Dispatchers.IO){inbox.bundles().single{it.bundleId==id}.tracks to inbox.excluded(id).toList()}}
        .onSuccess{data=it}.onFailure{failed=true}}
    if(data==null){AppText(if(failed)"수신 기록 확인 실패 · 원본 보존" else "수신 기록 확인 중");return}
    MobileV12Review(record,data!!.first,data!!.second,close,onSelectionChanged={excluded->
        UiWriteDispatch.submit({inbox.setExcluded(id,excluded.toSet())},{scope.launch{failed=true}})
    }) { included,done ->
        UiWriteDispatch.submit({
            // Reconcile the actual save selection in FIFO even after a cancelled gesture/recomposition.
            inbox.setExcluded(id,data!!.first.map{it.id}.toSet()-included.toSet())
            val pins=inbox.saveHearts(id,archives,java.time.Instant.now().toString())
            scope.launch {saved();done(CarV10SaveResult(pins.joinToString(","){it.id},"gleo Hearts에 저장했습니다 · 수신 확인과 별도"))}
        },{scope.launch{done(CarV10SaveResult(null,"저장 실패 · 원본과 선택 유지"))}})
    }
}
