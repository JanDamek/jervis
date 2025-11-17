package com.jervis.ui

import com.jervis.dto.events.DebugEventDto
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * Multiplatform WebSocket client for debug events (LLM calls)
 * Works on Android, iOS, and Desktop
 */
class DebugWebSocketClient(private val serverBaseUrl: String) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val _debugEvents = MutableSharedFlow<DebugEventDto>(replay = 0)
    val debugEvents: SharedFlow<DebugEventDto> = _debugEvents

    private val isRunning = atomic(false)

    /**
     * Start WebSocket connection with automatic reconnection
     */
    fun start() {
        if (!isRunning.compareAndSet(expect = false, update = true)) return

        scope.launch {
            var reconnectDelay = 1000L
            val maxReconnectDelay = 30000L

            while (isActive && isRunning.value) {
                try {
                    connect()
                    reconnectDelay = 1000L
                } catch (e: Exception) {
                    println("Debug WebSocket connection failed: ${e.message}. Reconnecting in ${reconnectDelay}ms...")
                    delay(reconnectDelay)
                    reconnectDelay = min(reconnectDelay * 2, maxReconnectDelay)
                }
            }
        }
    }

    private suspend fun connect() {
        val wsUrl = serverBaseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
            .plus("ws/debug")

        createPlatformHttpClient().use { client ->
            client.webSocket(
                urlString = wsUrl,
                request = {
                    // WebSocket requires explicit headers - defaultRequest doesn't work
                    headers.append(com.jervis.api.SecurityConstants.CLIENT_HEADER, com.jervis.api.SecurityConstants.CLIENT_TOKEN)
                    headers.append(com.jervis.api.SecurityConstants.PLATFORM_HEADER, getPlatformName())
                    try {
                        val localIp = getLocalIpAddress()
                        if (localIp != null) {
                            headers.append(com.jervis.api.SecurityConstants.CLIENT_IP_HEADER, localIp)
                        }
                    } catch (e: Exception) {
                        // Ignore - IP is optional
                    }
                }
            ) {
                println("Debug WebSocket connected to $wsUrl")
                try {
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        handleMessage(text)
                    }
                } finally {
                    println("Debug WebSocket disconnected")
                }
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        runCatching {
            println("Debug event received: ${text.take(100)}")
            val event = json.decodeFromString<DebugEventDto>(text)
            _debugEvents.emit(event)
        }.onFailure {
            println("Failed to parse debug event: ${it.message}")
        }
    }

    /**
     * Stop WebSocket connection
     */
    fun stop() {
        isRunning.value = false
        scope.cancel()
    }
}

/**
 * Create platform-specific HTTP client with WebSocket support
 * Expect declaration for platform-specific implementation
 */
expect fun createPlatformHttpClient(): HttpClient

/**
 * Get platform name (iOS, Android, Desktop)
 */
expect fun getPlatformName(): String

/**
 * Get local IP address (best effort)
 */
expect fun getLocalIpAddress(): String?
