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

    /**
     * Current reconnect attempt number (0 = not retrying).
     * Exposed to UI for display in the disconnect overlay.
     */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

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
                _reconnectAttempt.value = attempt

                // Create fresh HttpClient (critical for iOS Darwin engine after background)
                val newHttpClient = NetworkModule.createHttpClient()

                // Test basic HTTPS connectivity first
                try {
                    newHttpClient.get("${baseUrl.trimEnd('/')}/")
                } catch (e: Exception) {
                    println("RpcConnectionManager: HTTP test failed: ${e.message}")
                    try { newHttpClient.close() } catch (_: Exception) {}
                    throw e
                }

                // Create RPC client (opens WebSocket to /rpc)
                val newRpcClient = NetworkModule.createRpcClient(baseUrl, newHttpClient)
                val newServices = NetworkModule.createServices(newRpcClient)

                // Verify RPC connection with a lightweight call before declaring Connected.
                // This prevents false "connected" state when server responds to HTTP
                // but RPC/WebSocket services aren't ready yet (e.g., during server restart).
                try {
                    newServices.clientService.getAllClients()
                } catch (e: Exception) {
                    println("RpcConnectionManager: RPC verification failed: ${e.message}")
                    try { newRpcClient.close() } catch (_: Exception) {}
                    try { newHttpClient.close() } catch (_: Exception) {}
                    throw e
                }

                // Store references
                httpClient = newHttpClient
                rpcClient = newRpcClient
                currentServices = newServices

                // Signal connected
                _state.value = RpcConnectionState.Connected(newServices)
                _generation.value++
                attempt = 0
                _reconnectAttempt.value = 0

                println("RpcConnectionManager: Connected (generation=${_generation.value})")

                // Start active health monitoring
                monitorConnection(newServices)

                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                _reconnectAttempt.value = attempt
                // Minimum 5s delay between attempts, increasing linearly, capped at 30s
                val delayMs = min(5000L + 5000L * min(attempt - 1, 5).toLong(), 30_000L)
                println("RpcConnectionManager: Connection failed (attempt $attempt), retry in ${delayMs}ms: ${e.message}")
                _state.value = RpcConnectionState.Disconnected
                delay(delayMs)
            }
        }
    }

    /**
     * Active connection health monitoring via periodic heartbeat.
     * Tests connection every 30s to detect silent failures.
     */
    private fun monitorConnection(services: NetworkModule.Services) {
        scope.launch {
            while (true) {
                delay(30_000) // 30s heartbeat

                // Only check if we think we're connected
                if (_state.value !is RpcConnectionState.Connected) {
                    break
                }

                try {
                    // Lightweight health check - just fetch clients (small operation)
                    services.clientService.getAllClients()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("RpcConnectionManager: Heartbeat failed: ${e.message}")
                    _state.value = RpcConnectionState.Disconnected
                    delay(5000) // 5s cooldown before reconnect to prevent flickering
                    _generation.value++
                    reconnect()
                    break
                }
            }
        }
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
        generation.flatMapLatest { gen ->
            flow {
                println("RpcConnectionManager: resilientFlow restarting (gen=$gen), waiting for Connected state...")
                // Wait until we're connected
                val connected = state.first { it is RpcConnectionState.Connected }
                val services = (connected as RpcConnectionState.Connected).services
                println("RpcConnectionManager: resilientFlow got Connected (gen=$gen), subscribing...")
                emitAll(
                    subscribe(services).catch { e ->
                        if (e is CancellationException) throw e
                        // kRPC wraps cancellation as IllegalStateException("RpcClient was cancelled")
                        // This happens after server restart — treat as disconnect and reconnect
                        if (e is IllegalStateException && e.message?.contains("cancelled") == true) {
                            println("RpcConnectionManager: RPC client cancelled (gen=$gen), triggering reconnect")
                            _state.value = RpcConnectionState.Disconnected
                            scope.launch {
                                delay(5000)
                                _generation.value++
                                reconnect()
                            }
                            return@catch
                        }
                        println("RpcConnectionManager: Stream error (gen=$gen): ${e::class.simpleName}: ${e.message}")
                        e.printStackTrace()
                        // Mark disconnected and trigger reconnect after cooldown
                        // to prevent rapid flickering during server restart
                        _state.value = RpcConnectionState.Disconnected
                        scope.launch {
                            delay(5000) // 5s cooldown before reconnect attempt
                            _generation.value++
                            reconnect()
                        }
                    },
                )
            }
        }
}
