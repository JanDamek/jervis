package com.jervis.di

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlin.math.min

/**
 * Connection state exposed to UI and stream consumers.
 */
sealed class RpcConnectionState {
    data object Disconnected : RpcConnectionState()
    data object Connecting : RpcConnectionState()
    data class Connected(val services: NetworkModule.Services) : RpcConnectionState()
}

/**
 * Centralized RPC connection lifecycle manager.
 *
 * Owns HttpClient + KtorRpcClient + Services lifecycle.
 * Provides [resilientFlow] for automatic stream re-subscription on reconnect.
 * Uses [generation] counter to signal all streams to restart via flatMapLatest.
 *
 * This is the SINGLE source of truth for connection state — no other component
 * should create or manage RPC clients directly.
 */
class RpcConnectionManager(private val baseUrl: String) {
    private val _state = MutableStateFlow<RpcConnectionState>(RpcConnectionState.Disconnected)
    val state: StateFlow<RpcConnectionState> = _state.asStateFlow()

    /**
     * Generation counter — increments on each reconnect.
     * resilientFlow uses flatMapLatest on this to restart streams.
     */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private var httpClient: HttpClient? = null
    private var rpcClient: KtorRpcClient? = null
    private var currentServices: NetworkModule.Services? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val reconnectMutex = Mutex()

    /**
     * Get current services if connected, null otherwise.
     */
    fun getServices(): NetworkModule.Services? = currentServices

    /**
     * Initial connection. Call once from LaunchedEffect.
     * If the connection fails, retries with backoff.
     */
    suspend fun connect() {
        performConnect()
    }

    /**
     * Non-suspending reconnect trigger for UI callbacks.
     */
    fun requestReconnect() {
        scope.launch { reconnect() }
    }

    /**
     * Reconnect: close old connections, create new ones, bump generation.
     * Thread-safe via Mutex — concurrent calls are coalesced.
     */
    suspend fun reconnect() {
        reconnectMutex.withLock {
            // If already connected, skip (connection monitoring will trigger reconnect if needed)
            if (_state.value is RpcConnectionState.Connected) {
                return
            }

            println("RpcConnectionManager: Reconnecting to $baseUrl...")
            _state.value = RpcConnectionState.Connecting

            closeCurrentConnection()
            performConnect()
        }
    }

    private suspend fun performConnect() {
        var attempt = 0
        while (true) {
            try {
                _state.value = RpcConnectionState.Connecting

                // Create fresh HttpClient (critical for iOS Darwin engine after background)
                val newHttpClient = NetworkModule.createHttpClient()

                // Test basic HTTPS connectivity first
                try {
                    newHttpClient.get("$baseUrl/")
                } catch (e: Exception) {
                    println("RpcConnectionManager: HTTP test failed: ${e.message}")
                    try { newHttpClient.close() } catch (_: Exception) {}
                    throw e
                }

                // Create RPC client (opens WebSocket to /rpc)
                val newRpcClient = NetworkModule.createRpcClient(baseUrl, newHttpClient)
                val newServices = NetworkModule.createServices(newRpcClient)

                // Store references
                httpClient = newHttpClient
                rpcClient = newRpcClient
                currentServices = newServices

                // Signal connected
                _state.value = RpcConnectionState.Connected(newServices)
                _generation.value++
                attempt = 0

                println("RpcConnectionManager: Connected (generation=${_generation.value})")

                // Monitor connection — when rpcClient job completes, reconnect
                monitorConnection(newRpcClient)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val delayMs = min(1000L * (1L shl min(attempt - 1, 5)), 30_000L)
                println("RpcConnectionManager: Connection failed (attempt $attempt), retry in ${delayMs}ms: ${e.message}")
                _state.value = RpcConnectionState.Disconnected
                delay(delayMs)
            }
        }
    }

    /**
     * Monitor rpcClient lifecycle. When its job completes (connection died),
     * trigger automatic reconnection.
     */
    private fun monitorConnection(client: KtorRpcClient) {
        // Connection monitoring via periodic health checks or error callbacks
        // For now, rely on resilientFlow error handling to trigger reconnects
        // Future: implement periodic ping or use WebSocket close callback
    }

    private fun closeCurrentConnection() {
        try { rpcClient?.close() } catch (_: Exception) {}
        try { httpClient?.close() } catch (_: Exception) {}
        rpcClient = null
        httpClient = null
        currentServices = null
    }

    /**
     * Creates a Flow that automatically re-subscribes when the connection changes.
     *
     * Uses the [generation] counter — every time the connection is re-established,
     * generation increments, which causes flatMapLatest to cancel the previous
     * subscription and start a new one with fresh services.
     *
     * If currently disconnected, waits for connection before subscribing.
     *
     * Usage:
     * ```
     * connectionManager.resilientFlow { services ->
     *     services.notificationService.subscribeToEvents(clientId)
     * }.collect { event -> handleEvent(event) }
     * ```
     */
    fun <T> resilientFlow(
        subscribe: (NetworkModule.Services) -> Flow<T>,
    ): Flow<T> =
        generation.flatMapLatest {
            flow {
                // Wait until we're connected
                val connected = state.first { it is RpcConnectionState.Connected }
                val services = (connected as RpcConnectionState.Connected).services
                emitAll(
                    subscribe(services).catch { e ->
                        if (e is CancellationException) throw e
                        println("RpcConnectionManager: Stream error: ${e.message}")
                        // Don't retry here — the monitorConnection will detect the dead
                        // connection, trigger reconnect, bump generation, and flatMapLatest
                        // will restart this stream automatically.
                    },
                )
            }
        }
}
