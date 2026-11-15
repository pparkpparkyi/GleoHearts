package com.example.gleo_type.ui.app

import com.example.gleo_type.recommendation.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class CarV10StateAdapterTest {
    private var transport=CarConnectionSnapshot(CarConnectionState.CONNECTED,7)
    private var drive:String?="drive"
    private var input=DrivingRecommendationInput("run","drive",10000,7,"available",null,35.0,
        observedDurationMs=8000,coverage=.8,latestObservedElapsedMs=9900)
    private val adapter=CarV10StateAdapter({transport},{drive},{input},{10000},"run")
    @Test fun validLiveContextDisplaysObservedAverageOnly(){assertTrue(adapter.snapshot().available);assertTrue(adapter.snapshot().label.contains("35.0"))}
    @Test fun disconnectAndRetryNeverDisplaySpeed(){for(s in listOf(CarConnectionState.STOPPED,CarConnectionState.CONNECTING,CarConnectionState.RETRY_WAIT,CarConnectionState.BLOCKED)){transport=transport.copy(state=s);assertFalse(adapter.snapshot().available);assertNull(adapter.snapshot().context)}}
    @Test fun reconnectGenerationRequiresFreshInput(){transport=transport.copy(token=8);assertEquals("connection_generation_mismatch",adapter.snapshot().reason);input=input.copy(connectionToken=8,quality="unknown",fallbackReason="insufficient_post_connection_observation");assertFalse(adapter.snapshot().available);input=input.copy(quality="available");assertTrue(adapter.snapshot().available)}
    @Test fun staleFutureAndMissingTimeAreUnknown(){for(t in listOf<Long?>(7000,10001,null)){input=input.copy(latestObservedElapsedMs=t);assertFalse(adapter.snapshot().available)}}
    @Test fun unknownWrongSessionRunAndReplayAreRejected(){input=input.copy(quality="unknown");assertFalse(adapter.snapshot().available);input=input.copy(quality="available",driveSessionId="other");assertFalse(adapter.snapshot().available);input=input.copy(driveSessionId="drive",runId="other");assertFalse(adapter.snapshot().available);input=input.copy(runId="run",source=ContextInputSource.REPLAY);assertFalse(adapter.snapshot().available)}
    @Test fun insufficientAndInvalidSpeedNeverBecomeZero(){for(speed in listOf<Double?>(null,Double.NaN,-1.0)){input=input.copy(averageSpeedKmh=speed);assertNull(adapter.snapshot().context)};input=input.copy(averageSpeedKmh=0.0,observedDurationMs=1000);assertFalse(adapter.snapshot().available);input=input.copy(observedDurationMs=8000,coverage=Double.NaN);assertFalse(adapter.snapshot().available)}
    @Test fun changedDuringReadIsRejected(){val a=CarV10StateAdapter({transport},{drive},{transport=transport.copy(token=9);input},{10000},"run");assertEquals("state_changed_during_read",a.snapshot().reason)}
}
