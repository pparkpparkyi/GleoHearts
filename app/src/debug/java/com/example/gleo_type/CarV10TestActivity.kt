package com.example.gleo_type

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject

/** Debug only. Never accesses GleoApplication.container, SDK, player or user repositories. */
class CarV10TestActivity:ComponentActivity() {
    companion object {
        /** In-process isolated render factory survives Activity recreation/rotation; no Intent payloads. */
        var recreationContent:(@Composable ()->Unit)?=null
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Only this isolated debug surface; no global display or lock settings are changed.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(recreationContent!=null){setContent {recreationContent?.invoke()};return}
        if(intent.getBooleanExtra("render_fixture",false))setContent {
            var playback by remember { mutableStateOf(PlaybackUi(connected=true,
                track=Track("SYNTHETIC_CAR_V10","UI 검증용 음악","테스트 메타데이터"))) }
            val state=CarV10UiState(playback=playback,
                dark=intent.getBooleanExtra("dark",true),sourceDisclosure="격리 UI 테스트 · 실제 재생 아님")
            val actions=object:CarV10Actions{override fun toggle(){playback=playback.copy(playing=!playback.playing)}}
            if(intent.getStringExtra("screen")=="review") {
                val ids=(1..5).map{"SYNTHETIC_$it"}
                CarV10Review(DriveRecord("SYNTHETIC_RUN/DRIVE","DRIVE","2026-11-12T00:00:00Z","2026-11-12T00:01:00Z",ids,0.0,false,null,true),
                    ids.map{Track(it,it,"합성 테스트")},state.copy(ux=if(intent.getBooleanExtra("safe",false))DrivingUx(true,false,0,"SYNTHETIC_UI") else DrivingUx()),actions)
            } else CarV10Player(state,actions)
        }
    }
    fun windowFacts():JSONObject {
        val dm=resources.displayMetrics
        val j=JSONObject().put("density",dm.density).put("densityDpi",dm.densityDpi).put("fontScale",resources.configuration.fontScale)
            .put("multiWindow",isInMultiWindowMode).put("environment",android.os.Build.FINGERPRINT)
        if(android.os.Build.VERSION.SDK_INT>=30) {
            val m=windowManager.currentWindowMetrics;val i=m.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            j.put("windowBoundsPx",m.bounds.toShortString()).put("insetsPx",listOf(i.left,i.top,i.right,i.bottom).joinToString(","))
            j.put("availableWidthPx",m.bounds.width()-i.left-i.right).put("availableHeightPx",m.bounds.height()-i.top-i.bottom)
            val status=m.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars())
            val nav=m.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars())
            j.put("statusBarPx",listOf(status.left,status.top,status.right,status.bottom).joinToString(","))
            j.put("navigationBarPx",listOf(nav.left,nav.top,nav.right,nav.bottom).joinToString(","))
        }
        return j
    }
}
