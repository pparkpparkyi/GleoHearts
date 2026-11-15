package com.example.gleo_type.vehicle

enum class CarConnectionState { STOPPED, CONNECTING, CONNECTED, RETRY_WAIT, EXHAUSTED, BLOCKED, RELEASED }
data class CarConnectionSnapshot(val state: CarConnectionState = CarConnectionState.STOPPED,
    val token: Long = 0, val attempt: Int = 0, val subscriptions: Int = 0,
    val nextDelayMs: Long? = null, val reason: String? = null)

/** All mutations and scheduled callbacks run on one owner thread. No Android or session ownership. */
class CarConnectionSupervisor(
    private val scheduler: Scheduler,
    private val connection: Connection,
    private val changed: (CarConnectionSnapshot) -> Unit = {},
    private val maxAttempts: Int = 8,
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
    private val readyTimeoutMs: Long = 5_000,
) {
    fun interface Cancellation { fun cancel() }
    fun interface Scheduler { fun schedule(delayMs: Long, action: () -> Unit): Cancellation }
    interface Connection {
        fun open(token: Long, ready: (Boolean) -> Unit)
        /** Must clean up partial registrations on close; require the speed subscription. */
        fun subscribe(token: Long): Int
        fun close()
    }
    class Unavailable(message: String) : Exception(message)
    init { require(maxAttempts > 0 && baseDelayMs > 0 && maxDelayMs >= baseDelayMs && readyTimeoutMs > 0) }
    @Volatile var snapshot = CarConnectionSnapshot()
        private set
    private var running = false
    private var released = false
    private var serial = 0L
    private var attempt = 0
    private var cleanupPending = false
    private var pending: Cancellation? = null
    private fun state(value: CarConnectionState, delay: Long? = null, reason: String? = null, subscriptions: Int = 0) {
        snapshot = CarConnectionSnapshot(value, serial, attempt, subscriptions, delay, reason)
        runCatching { changed(snapshot) } // Diagnostics must not break cleanup or scheduling.
    }
    fun start() {
        if (running || released) return
        if (cleanupPending) {
            try { connection.close(); cleanupPending = false }
            catch (_: Exception) { state(CarConnectionState.BLOCKED, reason = "cleanup_failed"); return }
        }
        running = true; attempt = 0; connect()
    }
    fun stop() {
        running = false; serial++; pending?.cancel(); pending = null
        state(if (released) CarConnectionState.RELEASED else CarConnectionState.STOPPED)
        cleanupPending = runCatching { connection.close() }.isFailure
        if (cleanupPending) state(if (released) CarConnectionState.RELEASED else CarConnectionState.STOPPED,
            reason = "cleanup_failed")
    }
    fun release() { released = true; stop() }
    /** Explicit bounded retry/rebind for a caller while collection remains requested. */
    fun reconnect() {
        if (released) return
        if (!running) { start(); return }
        if (snapshot.state == CarConnectionState.CONNECTED) failed(serial, "client_requested_reconnect")
    }
    fun accepts(token: Long) = running && snapshot.state == CarConnectionState.CONNECTED && token == serial
    private fun connect() {
        if (!running) return
        attempt++; serial++
        val token = serial
        state(CarConnectionState.CONNECTING)
        pending = scheduler.schedule(readyTimeoutMs) { failed(token, "ready_timeout") }
        try { connection.open(token) { ready -> lifecycle(token, ready) } }
        catch (e: Exception) { failed(token, e.message ?: e.javaClass.simpleName, e is Unavailable) }
    }
    private fun lifecycle(token: Long, ready: Boolean) {
        if (!running || token != serial) return
        if (!ready) { failed(token, "service_disconnected"); return }
        if (snapshot.state != CarConnectionState.CONNECTING) return // Repeated ready is not a second subscription.
        try {
            val count = connection.subscribe(token)
            require(count > 0)
            pending?.cancel(); pending = null; attempt = 0
            state(CarConnectionState.CONNECTED, subscriptions = count)
        } catch (e: Exception) { failed(token, e.message ?: e.javaClass.simpleName, e is Unavailable) }
    }
    private fun failed(token: Long, reason: String, permanent: Boolean = false) {
        if (!running || token != serial) return
        serial++ // Invalidate old property/lifecycle callbacks before cleanup.
        pending?.cancel(); pending = null
        val terminal = permanent || attempt >= maxAttempts
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 20)
        val delay = if (baseDelayMs > maxDelayMs / multiplier) maxDelayMs else baseDelayMs * multiplier
        state(if (permanent) CarConnectionState.BLOCKED else if (terminal) CarConnectionState.EXHAUSTED
            else CarConnectionState.RETRY_WAIT, if (terminal) null else delay, reason)
        cleanupPending = runCatching { connection.close() }.isFailure
        if (cleanupPending) {
            running = false
            state(CarConnectionState.BLOCKED, reason = "cleanup_failed")
            return
        }
        if (terminal) { running = false; return }
        val generation = serial
        pending = scheduler.schedule(delay) { if (running && generation == serial) connect() }
    }
}
