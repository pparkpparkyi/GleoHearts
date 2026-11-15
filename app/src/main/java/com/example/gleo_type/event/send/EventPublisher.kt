package com.example.gleo_type.event.send

import com.example.gleo_type.event.core.EventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred

/**
 * SDK 콜백(일반 함수)에서 suspend send()를 호출하기 위한 연결부.
 * 콜백 스레드를 막지 않도록 scope에서 비동기로 전송한다.
 */
class EventPublisher(
    private val sender: EventSender,
    private val scope: CoroutineScope,
    private val onFailure: (EventDto, Throwable) -> Unit = { _, _ -> }
) {
    @Volatile var realtimeObserver: ((EventDto) -> Unit)? = null
    val realtimeObserverFailures = java.util.concurrent.atomic.AtomicLong()
    private data class Work(val action: suspend () -> Unit, val failed: (Throwable) -> Unit)
    private val queue = Channel<Work>(1024)
    private var draining = false

    fun publish(event: EventDto) {
        try { realtimeObserver?.invoke(event) }
        catch (_: Exception) { realtimeObserverFailures.incrementAndGet() }
        submit({ sender.send(event).getOrThrow() }, { onFailure(event, it) })
    }

    /** FIFO fence, not a shutdown. Prior writes have finished attempting when this returns.
     * Sender failures still use their existing callbacks; callers must verify expected records.
     * Await off the SDK/main thread with a caller-owned timeout. Later submissions may continue. */
    suspend fun awaitSubmittedWrites() {
        val done = CompletableDeferred<Unit>()
        submit({ done.complete(Unit); Unit }, { done.completeExceptionally(it) })
        done.await()
    }

    /** FIFO submission order, which may differ from timestamps across SDK threads. */
    @Synchronized
    fun submit(action: suspend () -> Unit, onFailure: (Throwable) -> Unit) {
        val result = queue.trySend(Work(action, onFailure))
        if (result.isFailure) {
            onFailure(result.exceptionOrNull() ?: IllegalStateException("Event queue full (1024)"))
            return
        }
        if (draining) return
        draining = true
        scope.launch {
            while (true) {
                val work = synchronized(this@EventPublisher) {
                    queue.tryReceive().getOrNull().also { if (it == null) draining = false }
                } ?: break
                try { work.action() } catch (error: Exception) { work.failed(error) }
            }
        }
    }
}
