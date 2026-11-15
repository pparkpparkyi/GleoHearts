package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.Track
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceExecutionCoordinatorTest {
    private class Rig {
        var now=10_000L;var count=0
        val root=File(System.getProperty("java.io.tmpdir"),"gleo-voice-test-"+UUID.randomUUID())
        val input=DrivingRecommendationInput("run","drive",now,null,"unknown","missing")
        val snapshots=ContextSnapshotProvider("user","run",ContextObservationStore{"drive"},{input},{now},"isolated_test")
        val ledger=PipelineLedger(File(root,"ledger.jsonl"))
        val pipeline=ContextPipeline("user","run",{"drive"},snapshots,ledger,{now})
        val port=object:VoicePlaybackPort {
            override fun catalog()=emptyList<Track>()
            override fun current()=VoicePlaybackObservation(null,null,false,0)
            override fun start(ticket:PlaybackTicket){error("Must not start")}
            override fun control(intent:CommandIntent){error("Must not control")}
        }
        val executor=VoiceExecutionCoordinator("user","run",{"drive"},pipeline,RecommendationModelAdapter(root,"user"),
            FusionRuntime(LocalClapTextEncoder{null},ExperienceCaseRepository.forUser(root,"user")),port,{now},{now+=it}){count++}
        val action=GleoVoiceContract.Action(GleoVoiceContract.CONTROL,CommandIntent.PLAY,null)
    }
    @Test fun officialUnverifiedSourceNeverExecutesOrEmitsPretendOfficialCallback() {
        val r=Rig();val result=r.executor.execute(r.action,CommandSource.OFFICIAL_GLEO_ACTION,"official-1",r.now)
        assertEquals("fail",result.status);assertEquals(0,r.count);assertFalse(r.root.exists())
    }
    @Test fun expiredAndFutureDeliveryFailWithoutStartingPlayer() {
        val r=Rig()
        assertEquals("fail",r.executor.execute(r.action,CommandSource.SIMULATED_GLEO_ACTION,"expired",r.now-6501).status)
        assertEquals("fail",r.executor.execute(r.action,CommandSource.SIMULATED_GLEO_ACTION,"future",r.now+1).status)
        assertEquals(2,r.count);assertFalse(r.root.exists())
    }
    @Test fun sameLocalTokenHasOneCallbackAndConflictingReuseIsRejected() {
        val r=Rig();val first=r.executor.execute(r.action,CommandSource.SIMULATED_GLEO_ACTION,"same",r.now)
        assertEquals("fail",first.status)
        assertEquals(first,r.executor.execute(r.action,CommandSource.SIMULATED_GLEO_ACTION,"same",r.now))
        assertEquals(1,r.count)
        assertTrue(runCatching{r.executor.execute(r.action.copy(intent=CommandIntent.PAUSE),CommandSource.SIMULATED_GLEO_ACTION,"same",r.now)}.isFailure)
        assertFalse(r.root.exists())
    }
}
