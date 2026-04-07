package com.jervis.di

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Connection state exposed to UI and stream consumers.
 *
 * Only [runConnectionLoop] writes to the state — all other callers use
 * [requestReconnect] to enqueue reconnect signals via a CONFLATED channel.
 */
sealed class RpcConnectionState {
    data object Disconnected : RpcConnectionState()
    data object Connecting : RpcConnectionState()
    data class Connected(
        val services: NetworkModule.Services,
        val generation: Long,
    ) : RpcConnectionState() {
        override fun toString() = "Connected(gen=$generation, ${services.countServices()} services)"
    }
}

/**
 * Centralized RPC connection lifecycle manager.
 *
 * ## Design
 *
 * - **Single writer of [_state]**: a dedicated [runConnectionLoop] coroutine is the
 *   sole writer. External callers only enqueue reconnect requests via [requestReconnect],
 *   which pushes into a CONFLATED channel. The loop consumes signals and transitions
 *   the FSM. No races, no "state changed mid-reconnect" windows.
 *
 * - **No application-layer heartbeat**. Disconnect detection is handled entirely by:
 *     1. WebSocket ping frames (Ktor `install(WebSockets) { pingInterval = 10.seconds }`
 *        on both client and server, server `timeoutMillis = 5_000`). Transport-level
 *        death is caught within ≤15 s with zero RPC traffic.
 *     2. Live subscribe streams in [resilientFlow] throw an exception on disconnect,
 *        which [isConnectionLost] routes to [requestReconnect].
 *     3. [rpcCall] wraps user-initiated RPC calls with `withTimeout` + automatic
 *        reconnect on connection-lost errors.
 *   Heartbeat has been added and removed ~20 times in this codebase. It only adds
 *   server load without solving any actual reconnect problem.
 *
 * - **Resilient streams**: [resilientFlow] uses an infinite `while (isActive)` loop
 *   inside a [channelFlow]. The loop's only exit is upstream cancellation — it cannot
 *   complete silently. On connection loss it waits for state to leave Connected before
 *   re-subscribing, preventing hot loops on the same dead socket.
 *
 * - **Resilient calls**: [rpcCall] wraps one-shot RPC calls with bounded
 *   `awaitConnected` + `withTimeout` + automatic reconnect on connection-lost errors.
 *
 * ## Invariants
 *
 * 1. Only [runConnectionLoop] writes [_state].
 * 2. [_generation] is monotonic, bumped exactly once per `Connected` entry.
 * 3. [resilientFlow] cannot complete silently — only path out is upstream cancellation.
 * 4. Reconnect signals coalesce via CONFLATED channel — no storm.
 *
 * ## Failure mode coverage
 *
 * - `kubectl rollout restart` → old pod close frame → stream throws → `isConnectionLost` → reconnect
 * - `kubectl delete pod` (no close frame) → WebSocket ping timeout (5 s) → stream exception → reconnect
 * - Wi-Fi off → WebSocket ping timeout → stream exception → reconnect backoff loop
 * - Laptop sleep/wake → first post-wake ping fails → stream exception → reconnect
 * - Network switch → socket dies → stream exception → reconnect on new IP
 * - Long idle with no subscribe streams → transport ping keeps socket alive; if it dies,
 *   the next rpcCall detects it and triggers reconnect
 * - Server unreachable for minutes → backoff caps at [maxBackoffMs], recovers within that
 */
class RpcConnectionManager(val baseUrl: String) {

    // ─── Tunables ──────────────────────────────────────────────────
    // NOTE: NO application-layer heartbeat. Disconnect detection is handled by:
    //   1. WebSocket ping frames — client pingInterval=10s, server pingPeriodMillis=10_000,
    //      timeoutMillis=5_000. Transport-layer death is caught within ≤15s with zero RPC load.
    //   2. Live subscribe streams (resilientFlow) throw exceptions on disconnect, which
    //      isConnectionLost() catches and routes to requestReconnect.
    //   3. rpcCall() wraps user-initiated calls with withTimeout + auto-reconnect.
    // Heartbeat has been added and removed ~20 times in this codebase. It only spams the
    // server and solves nothing — the real problems were always elsewhere (silent flow
    // completion, string-based exception match). See KB: "desktop reconnect no heartbeat".
    private val rpcCallDefaultTimeout = 10.seconds
    private val rpcVerifyTimeout = 8.seconds
    private val baseBackoffMs = 1_000L
    private val maxBackoffMs = 15_000L
    private val postDisconnectCooldown = 200.milliseconds
    private val safetyLoopNap = 100.milliseconds

    // ─── Public state ─────────────────────────────────────────────
    private val _state = MutableStateFlow<RpcConnectionState>(RpcConnectionState.Disconnected)
    val state: StateFlow<RpcConnectionState> = _state.asStateFlow()

    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ─── Internals ────────────────────────────────────────────────
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    /** CONFLATED: multiple reconnect requests in the same tick coalesce into one. */
    private val reconnectChannel = Channel<String>(Channel.CONFLATED)

    private var connectionLoopJob: Job? = null

    private var httpClient: HttpClient? = null
    private var rpcClient: KtorRpcClient? = null
    private var currentServices: NetworkModule.Services? = null

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sync accessor for the current services bag. Returns null when not Connected.
     * Used by [JervisRepository] accessors for backwards compatibility with direct
     * (non-`rpcCall`) access patterns.
     */
    fun getServices(): NetworkModule.Services? = currentServices

    /**
     * Idempotent. Starts the connection loop on first call. Subsequent calls are no-ops.
     */
    fun start() {
        if (connectionLoopJob?.isActive == true) return
        connectionLoopJob = scope.launch { runConnectionLoop() }
    }

    /** Backwards-compat alias for existing `manager.connect()` call sites. */
    suspend fun connect() {
        start()
    }

    /**
     * Fire-and-forget reconnect request. Coalesces with concurrent calls via CONFLATED channel.
     * Safe to call from any coroutine or callback.
     */
    fun requestReconnect(reason: String = "manual") {
        _lastError.value = reason
        reconnectChannel.trySend(reason)
    }

    /** Legacy alias — some old callers use this name. */
    fun triggerReconnect(reason: String) = requestReconnect(reason)

    /**
     * Suspends until Connected or [timeout] elapses (then throws).
     * Returns the current services bag.
     */
    suspend fun awaitConnected(timeout: Duration = rpcCallDefaultTimeout): NetworkModule.Services {
        val snapshot = state.value
        if (snapshot is RpcConnectionState.Connected) return snapshot.services
        return try {
            withTimeout(timeout) {
                (
                    state.first { it is RpcConnectionState.Connected }
                        as RpcConnectionState.Connected
                ).services
            }
        } catch (e: TimeoutCancellationException) {
            throw ConnectionTimeoutException("awaitConnected: not connected within $timeout")
        }
    }

    /**
     * Resilient one-shot RPC call.
     *
     * 1. `awaitConnected(timeout)` — bounded wait for Connected state.
     * 2. `withTimeout(timeout) { block(services) }` — bounded execution.
     * 3. On `TimeoutCancellationException` → enqueue reconnect, throw [OfflineException].
     * 4. On any throwable where `isConnectionLost(it) == true` → enqueue reconnect,
     *    throw [OfflineException]. Real server errors propagate unchanged.
     *
     * Use this for any user-initiated action where you want transparent wait-for-reconnect
     * + fast failure on dead sockets. The existing `repository.chat.sendMessage(...)` accessors
     * still work for code that doesn't need this behavior.
     */
    suspend fun <T> rpcCall(
        timeout: Duration = rpcCallDefaultTimeout,
        block: suspend (NetworkModule.Services) -> T,
    ): T {
        val services = awaitConnected(timeout)
        return try {
            withTimeout(timeout) { block(services) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: TimeoutCancellationException) {
            requestReconnect("rpcCall timeout after $timeout")
            throw OfflineException("RPC call timed out — reconnecting")
        } catch (e: Throwable) {
            if (isConnectionLost(e)) {
                requestReconnect("rpcCall conn-lost: ${e::class.simpleName}")
                throw OfflineException("Connection lost during RPC call: ${e.message}")
            }
            throw e
        }
    }

    /**
     * Resilient subscription Flow.
     *
     * Wraps a server-side subscribe call in an infinite `while (isActive)` loop:
     *
     * ```
     * while (isActive) {
     *     (services, gen) = state.first { it is Connected } as Connected
     *     coroutineScope {
     *         launch { subscribe(services).collect { send(it) } }   // collectJob
     *         launch { generation.first { it != gen }; cancel }     // watcherJob
     *     }
     *     // If we got here: either stream completed, or generation flipped, or error.
     *     // On error: requestReconnect, then wait for state to leave Connected.
     * }
     * ```
     *
     * **The loop can NEVER complete silently.** Its only exit is upstream cancellation
     * (the caller's `collect {}` cancelling us). Every error path re-enters the loop,
     * waits for the next Connected state (via requestReconnect + state.first), and
     * re-subscribes with the fresh services.
     *
     * Usage:
     * ```
     * scope.launch {
     *     connectionManager.resilientFlow { services ->
     *         services.chatService.subscribeToChatEvents()
     *     }.collect { event -> handleEvent(event) }
     * }
     * ```
     */
    fun <T> resilientFlow(
        subscribe: (NetworkModule.Services) -> Flow<T>,
    ): Flow<T> = channelFlow {
        while (isActive) {
            val (services, subscribedGen) = try {
                val c = state.first { it is RpcConnectionState.Connected }
                        as RpcConnectionState.Connected
                c.services to c.generation
            } catch (ce: CancellationException) {
                throw ce
            }

            println("RpcConnectionManager.resilientFlow: subscribing (gen=$subscribedGen)")

            try {
                coroutineScope {
                    val collectJob = launch {
                        subscribe(services).collect { item -> send(item) }
                    }
                    val watcherJob = launch {
                        // Wait for generation to flip → reconnect happened mid-stream → restart
                        generation.first { it != subscribedGen }
                        println("RpcConnectionManager.resilientFlow: generation flipped " +
                            "(was=$subscribedGen), cancelling inner collect")
                        collectJob.cancel(CancellationException("generation changed"))
                    }
                    collectJob.join()
                    watcherJob.cancel()
                }
            } catch (ce: CancellationException) {
                // (a) Upstream collector cancelled us → propagate and exit loop
                // (b) Inner cancelled via generation watcher → continue loop
                if (!isActive) throw ce
                println("RpcConnectionManager.resilientFlow: cancelled internally, continuing loop")
            } catch (e: Throwable) {
                if (isConnectionLost(e)) {
                    println("RpcConnectionManager.resilientFlow: conn-lost error " +
                        "(${e::class.simpleName}: ${e.message}), requesting reconnect")
                    requestReconnect("stream conn-lost: ${e::class.simpleName}")
                } else {
                    println("RpcConnectionManager.resilientFlow: non-conn error " +
                        "(${e::class.simpleName}: ${e.message}), requesting reconnect")
                    requestReconnect("stream error: ${e::class.simpleName}")
                }
                // Wait for state to leave Connected before re-subscribing,
                // so we don't immediately re-subscribe on the same dead socket.
                try {
                    state.first { it !is RpcConnectionState.Connected }
                } catch (ce: CancellationException) {
                    throw ce
                }
            }
            // Safety nap to avoid hot-loops in pathological cases
            delay(safetyLoopNap)
        }
    }

    /** Stop everything. After this call, the manager is dead. */
    fun shutdown() {
        connectionLoopJob?.cancel()
        closeCurrentConnection()
        supervisor.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // Connection FSM (single writer of _state)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun runConnectionLoop() {
        var attempt = 0
        while (scope.isActive) {
            // ── Entering Connecting ──
            _state.value = RpcConnectionState.Connecting
            _reconnectAttempt.value = attempt

            val ok = tryConnect()
            if (!ok) {
                attempt++
                _reconnectAttempt.value = attempt
                val delayMs = backoffMs(attempt)
                _state.value = RpcConnectionState.Disconnected
                println("RpcConnectionManager: connect failed (attempt=$attempt), " +
                    "retry in ${delayMs}ms — lastError=${_lastError.value}")
                // Allow a manual reconnect signal to short-circuit the backoff.
                withTimeoutOrNull(delayMs) { reconnectChannel.receive() }
                continue
            }

            // ── Entering Connected ──
            attempt = 0
            _reconnectAttempt.value = 0
            _lastError.value = null
            val gen = _generation.value + 1
            _generation.value = gen
            val services = currentServices
                ?: error("currentServices is null after successful tryConnect — should not happen")
            _state.value = RpcConnectionState.Connected(services, gen)
            println("RpcConnectionManager: Connected (gen=$gen)")

            // ── Wait for a reconnect signal ──
            // No heartbeat: disconnect detection is fully handled by
            //   (a) WebSocket ping frames (10s on both ends) — transport level,
            //   (b) exceptions from live subscribe streams via resilientFlow,
            //   (c) rpcCall's withTimeout + isConnectionLost on user-initiated calls.
            val reason = try {
                reconnectChannel.receive()
            } catch (ce: CancellationException) {
                closeCurrentConnection()
                _state.value = RpcConnectionState.Disconnected
                throw ce
            }
            println("RpcConnectionManager: reconnect requested — $reason")

            // ── Transition back to Disconnected ──
            _state.value = RpcConnectionState.Disconnected
            closeCurrentConnection()
            delay(postDisconnectCooldown)
        }
    }

    private suspend fun tryConnect(): Boolean = try {
        val newHttp = NetworkModule.createHttpClient()

        // Phase 1: HTTP probe — fails fast on DNS / firewall / server-down
        try {
            withTimeout(rpcVerifyTimeout) {
                newHttp.get("${baseUrl.trimEnd('/')}/")
            }
        } catch (ce: CancellationException) {
            runCatching { newHttp.close() }
            throw ce
        } catch (e: Throwable) {
            runCatching { newHttp.close() }
            _lastError.value = "HTTP probe failed: ${e::class.simpleName}: ${e.message}"
            return false
        }

        // Phase 2: kRPC handshake + service creation
        val newRpc = NetworkModule.createRpcClient(baseUrl, newHttp)
        val newServices = NetworkModule.createServices(newRpc)

        // Phase 3: RPC verification call — ensures WebSocket + kRPC handshake is live
        try {
            withTimeout(rpcVerifyTimeout) {
                newServices.clientService.getAllClients()
            }
        } catch (ce: CancellationException) {
            runCatching { newRpc.close() }
            runCatching { newHttp.close() }
            throw ce
        } catch (e: Throwable) {
            runCatching { newRpc.close() }
            runCatching { newHttp.close() }
            _lastError.value = "RPC verify failed: ${e::class.simpleName}: ${e.message}"
            return false
        }

        // Store references only after all three phases succeed
        httpClient = newHttp
        rpcClient = newRpc
        currentServices = newServices
        true
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        _lastError.value = "tryConnect: ${e::class.simpleName}: ${e.message}"
        false
    }

    private fun closeCurrentConnection() {
        runCatching { rpcClient?.close() }
        runCatching { httpClient?.close() }
        rpcClient = null
        httpClient = null
        currentServices = null
    }

    /** Exponential backoff capped at [maxBackoffMs]. 1s, 2s, 4s, 8s, 15s, 15s, ... */
    private fun backoffMs(attempt: Int): Long {
        val capped = min(attempt - 1, 4)  // 2^4 = 16 ≈ cap
        val exp = baseBackoffMs * (1L shl capped)
        return min(exp, maxBackoffMs)
    }

}
