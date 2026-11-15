package com.example.gleo_type.vehicle

import com.example.gleo_type.event.core.DriveSessionManager
import com.example.gleo_type.event.vehicle.SpeedSampleRecorder
import com.example.gleo_type.realtime.RealtimePipeline
import org.junit.Assert.*
import org.junit.Test

class CarConnectionSupervisorTest {
    private class Clock : CarConnectionSupervisor.Scheduler {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit): CarConnectionSupervisor.Cancellation {
            val t = Task(now + delayMs, action); tasks += t
            return CarConnectionSupervisor.Cancellation { t.cancelled = true }
        }
        fun advance(delta: Long) {
            val target = now + delta
            while (true) {
                val t = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                t.cancelled = true; now = t.at; t.action()
            }
            now = target
        }
    }
    private class Fake : CarConnectionSupervisor.Connection {
        var opens = 0; var subscriptions = 0; var concurrent = 0; var maximum = 0; var closes = 0
        var failOpen = false; var blocked = false; var failSubscribe = false; var failClose = false
        val callbacks = mutableListOf<(Boolean) -> Unit>(); var token = 0L
        override fun open(token: Long, ready: (Boolean) -> Unit) {
            opens++; this.token = token; callbacks += ready
            if (blocked) throw CarConnectionSupervisor.Unavailable("permission_or_API")
            if (failOpen) error("service_down")
        }
        override fun subscribe(token: Long): Int {
            concurrent++; maximum = maxOf(maximum, concurrent)
            if (failSubscribe) error("partial_registration")
            subscriptions++; return 1
        }
        override fun close() { closes++; if (failClose) error("dead_binder"); concurrent = 0 }
    }
    private class Rig(max: Int = 8, changed: (CarConnectionSnapshot) -> Unit = {}) {
        val clock = Clock(); val fake = Fake()
        val s = CarConnectionSupervisor(clock, fake, changed, max, 10, 40, 100)
        fun ready() = fake.callbacks.last()(true)
    }
    @Test fun initialSuccessAndRepeatedReadyDoNotDuplicateSubscriptions() {
        val r=Rig();r.s.start();r.s.start();r.ready();repeat(3) { r.ready() }
        assertEquals(1,r.fake.opens);assertEquals(1,r.fake.subscriptions)
        assertEquals(CarConnectionState.CONNECTED,r.s.snapshot.state);assertEquals(0,r.s.snapshot.attempt)
    }
    @Test fun initialFailureThenRecoveryResetsBackoff() {
        val r=Rig();r.fake.failOpen=true;r.s.start()
        assertEquals(10L,r.s.snapshot.nextDelayMs);r.clock.advance(10)
        assertEquals(20L,r.s.snapshot.nextDelayMs)
        r.fake.failOpen=false;r.clock.advance(20);r.ready();r.fake.callbacks.last()(false)
        assertEquals(10L,r.s.snapshot.nextDelayMs)
    }
    @Test fun disconnectRebindsAndOldLifecycleAndPropertyTokensAreRejected() {
        val r=Rig();r.s.start();r.ready();val old=r.fake.token;val cb=r.fake.callbacks.last()
        cb(false);assertFalse(r.s.accepts(old));r.clock.advance(10);r.ready()
        cb(true);cb(false);assertTrue(r.s.accepts(r.fake.token));assertFalse(r.s.accepts(old))
        assertEquals(2,r.fake.subscriptions);assertEquals(1,r.fake.maximum)
    }
    @Test fun repeatedFailureIsFiniteAndDelayIsCapped() {
        val delays=mutableListOf<Long>()
        val r=Rig(6,changed={ it.nextDelayMs?.let(delays::add) });r.fake.failOpen=true;r.s.start();r.clock.advance(10_000)
        assertEquals(listOf(10L,20L,40L,40L,40L),delays)
        assertEquals(6,r.fake.opens);assertEquals(CarConnectionState.EXHAUSTED,r.s.snapshot.state)
        assertNull(r.s.snapshot.nextDelayMs);r.clock.advance(10_000);assertEquals(6,r.fake.opens)
    }
    @Test fun readyTimeoutClosesTheUnreadyCarAndOldReadyCannotReviveIt() {
        val r=Rig();r.s.start();val cb=r.fake.callbacks.last();r.clock.advance(100)
        assertEquals(CarConnectionState.RETRY_WAIT,r.s.snapshot.state);cb(true)
        assertEquals(0,r.fake.subscriptions);assertEquals(1,r.fake.closes)
    }
    @Test fun manyReconnectionsHaveAtMostOneLiveRegistration() {
        val r=Rig();r.s.start();r.ready()
        repeat(20) { r.s.reconnect();r.clock.advance(10);r.ready() }
        assertEquals(21,r.fake.subscriptions);assertEquals(1,r.fake.maximum)
    }
    @Test fun stopCancelsRetryAndRejectsEvenAlreadyQueuedTimeouts() {
        val r=Rig();r.fake.failOpen=true;r.s.start();val queued=r.clock.tasks.last().action
        r.s.stop();queued();r.clock.advance(10_000)
        assertEquals(1,r.fake.opens);assertEquals(CarConnectionState.STOPPED,r.s.snapshot.state)
    }
    @Test fun releaseIsTerminalAndStartStopStartIsGenerationSafe() {
        val r=Rig();r.s.start();val old=r.fake.callbacks.last();r.s.stop();r.s.start();r.ready();old(false)
        assertEquals(CarConnectionState.CONNECTED,r.s.snapshot.state)
        r.s.release();r.s.start();r.s.reconnect();r.clock.advance(10_000)
        assertEquals(2,r.fake.opens);assertEquals(CarConnectionState.RELEASED,r.s.snapshot.state)
    }
    @Test fun permissionOrMissingApiDoesNotBusyRetryButExplicitRetryIsPossible() {
        val r=Rig();r.fake.blocked=true;r.s.start();r.clock.advance(10_000)
        assertEquals(1,r.fake.opens);assertEquals(CarConnectionState.BLOCKED,r.s.snapshot.state)
        r.fake.blocked=false;r.s.reconnect();r.ready();assertEquals(CarConnectionState.CONNECTED,r.s.snapshot.state)
    }
    @Test fun partialSubscribeAndCleanupErrorsDoNotCreateParallelSubscriptions() {
        val r=Rig();r.fake.failSubscribe=true;r.fake.failClose=true;r.s.start();r.ready()
        assertEquals(CarConnectionState.BLOCKED,r.s.snapshot.state)
        r.clock.advance(1000);assertEquals(1,r.fake.opens)
        r.fake.failSubscribe=false;r.fake.failClose=false;r.s.reconnect();r.ready();assertEquals(1,r.fake.maximum)
    }
    @Test fun diagnosticsExceptionsDoNotPreventConnectionOrCleanup() {
        val r=Rig(changed={ error("observer_failure") });r.s.start();r.ready();r.s.stop()
        assertEquals(1,r.fake.closes);assertEquals(CarConnectionState.STOPPED,r.s.snapshot.state)
    }
    @Test fun speedResumesInSameRunDriveAndUnavailableDoesNotGenerateZero() {
        val r=Rig();val drives=DriveSessionManager { "drive" };val drive=drives.start()
        val realtime=RealtimePipeline("run",{r.clock.now});realtime.start(drive)
        val values=mutableListOf<Float>()
        val recorder=SpeedSampleRecorder { mps,elapsed,_ -> values+=mps;realtime.speed(drive,mps*3.6,elapsed,"test",null,null) }
        fun speed(value:Float) { if(r.s.accepts(r.fake.token)) recorder.accept(value,r.clock.now,null) }
        r.s.start();r.ready()
        repeat(11) { speed(10f);r.clock.advance(1000) }
        assertNotNull(realtime.usableContext())
        r.fake.callbacks.last()(false);val before=values.size;r.clock.advance(4000);speed(0f)
        assertEquals(before,values.size);assertNull(realtime.usableContext())
        r.s.reconnect();r.ready();repeat(12) { speed(20f);r.clock.advance(1000) }
        assertEquals(drive,drives.currentDriveSessionId());assertNotNull(realtime.usableContext())
        assertTrue(values.none { it==0f });assertEquals("run",realtime.usableContext()!!.session.runId)
        r.s.stop();speed(1f);realtime.stop(drive)
    }
}
