package com.example.gleo_type

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.vehicle.CarConnectionSnapshot
import java.io.File

/** Debug-only isolated profile; actual Media3, no Vehicle/GNSS initialization. */
class CarV10ModelTestActivity:ComponentActivity() {
    lateinit var playback:MediaPlaybackConnection
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=File(requireNotNull(intent.getStringExtra("modelRoot"))).canonicalFile
        require(root.toPath().startsWith(cacheDir.canonicalFile.toPath()))
        val c=(application as GleoApplication).container
        c.startCollection()
        setContent {
            val port=remember{MediaPlaybackConnection(applicationContext,c).also{playback=it}}
            DisposableEffect(port){onDispose{port.close()}}
            val model=remember{RecommendationModelAdapter(root,"SYNTHETIC_STEP4_PROFILE",PlaybackMappingVerifier(applicationContext)::verify,
                drivingContext=c.drivingContext::current)}
            val source=remember{CarMusicRecommendationSource(model,LocalRecommendationHistory(File(root,"recommendations")),
                CarMusicPresentationRepository(applicationContext,model::modelFingerprint,model::modelTrackIds),{emptySet()},
                appRunId={c.runId},driveId=c.driveSessions::currentDriveSessionId)}
            val vehicle=remember{CarV10StateAdapter({CarConnectionSnapshot()},c.driveSessions::currentDriveSessionId,c.drivingContext::current,
                android.os.SystemClock::elapsedRealtime,c.runId)}
            CarV10LivePlayer(port,vehicle,DrivingUx(),true,source,"합성 테스트 프로필 · 실제 Media3 · Vehicle SDK 미연결"){finish()}
        }
    }
    override fun onDestroy(){if(!isChangingConfigurations)(application as GleoApplication).container.stopCollection();super.onDestroy()}
}
