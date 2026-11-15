package com.example.gleo_type.event

import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.send.EventPublisher
import com.example.gleo_type.event.send.EventSender
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EventPublisherFenceTest {
    private val sender=object:EventSender{override suspend fun send(event:EventDto)=Result.success(Unit)}
    @Test fun fenceWaitsForPriorActionsInFifoOrder()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val publisher=EventPublisher(sender,scope);val release=CompletableDeferred<Unit>();val order=mutableListOf<Int>()
            publisher.submit({release.await();order.add(1);Unit},{throw AssertionError(it)})
            publisher.submit({order.add(2);Unit},{throw AssertionError(it)})
            val fence=async(start=CoroutineStart.UNDISPATCHED){publisher.awaitSubmittedWrites()}
            assertFalse(fence.isCompleted);release.complete(Unit);withTimeout(5000){fence.await()}
            assertEquals(listOf(1,2),order)
        } finally {scope.cancel()}
    }
    @Test fun failedWriteUsesExistingCallbackAndLaterWorkStillCompletes()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val publisher=EventPublisher(sender,scope);var failures=0;var later=false
            publisher.submit({error("disk failure")},{failures++})
            publisher.submit({later=true},{throw AssertionError(it)})
            withTimeout(5000){publisher.awaitSubmittedWrites()}
            assertEquals(1,failures);assertTrue(later)
        } finally {scope.cancel()}
    }
    @Test fun callerTimeoutDoesNotCancelOrDropTheWriterQueue()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val publisher=EventPublisher(sender,scope);val release=CompletableDeferred<Unit>();var written=false
            publisher.submit({release.await();written=true},{throw AssertionError(it)})
            assertNull(withTimeoutOrNull(20){publisher.awaitSubmittedWrites();true})
            release.complete(Unit);withTimeout(5000){publisher.awaitSubmittedWrites()};assertTrue(written)
        } finally {scope.cancel()}
    }
}
