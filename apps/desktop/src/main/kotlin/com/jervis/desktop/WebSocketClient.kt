package com.jervis.desktop

import com.jervis.dto.events.DebugEventDto
import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.dto.events.UserTaskCreatedEventDto
import com.jervis.dto.events.UserTaskCancelledEventDto
import com.jervis.dto.events.AgentResponseEventDto
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.min

/**
 * WebSocket client for receiving real-time notifications from Jervis server
 */
class WebSocketClient(private val serverBaseUrl: String) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        engine {
            https {
                // Accept all certificates for development (self-signed certificates)
                val trustAllCerts = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())

                trustManager = trustAllCerts
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = Long.MAX_VALUE
        }
    }

    // Event flows
    private val _userTaskEvents = MutableSharedFlow<UserTaskCreatedEventDto>(replay = 0)
    val userTaskEvents: SharedFlow<UserTaskCreatedEventDto> = _userTaskEvents

    private val _userTaskCancelledEvents = MutableSharedFlow<UserTaskCancelledEventDto>(replay = 0)
    val userTaskCancelledEvents: SharedFlow<UserTaskCancelledEventDto> = _userTaskCancelledEvents

    private val _errorEvents = MutableSharedFlow<ErrorNotificationEventDto>(replay = 0)
    val errorEvents: SharedFlow<ErrorNotificationEventDto> = _errorEvents

    private val _debugEvents = MutableSharedFlow<DebugEventDto>(replay = 0)
    val debugEvents: SharedFlow<DebugEventDto> = _debugEvents

    @Volatile
    private var isRunning = false

    /**
     * Start WebSocket connection with automatic reconnection
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            var reconnectDelay = 1000L
            val maxReconnectDelay = 30000L

            while (isActive && isRunning) {
                try {
                    connect()
                    reconnectDelay = 1000L
                } catch (e: Exception) {
                    println("WebSocket connection failed: ${e.message}. Reconnecting in ${reconnectDelay}ms...")
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
            .plus("ws/notifications")

        client.webSocket(
            urlString = wsUrl,
            request = {
                // WebSocket requires explicit headers - defaultRequest doesn't work
                headers.append(com.jervis.api.SecurityConstants.CLIENT_HEADER, com.jervis.api.SecurityConstants.CLIENT_TOKEN)
                headers.append(com.jervis.api.SecurityConstants.PLATFORM_HEADER, com.jervis.api.SecurityConstants.PLATFORM_DESKTOP)
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
            println("WebSocket connected to $wsUrl")
            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handleMessage(text)
                }
            } finally {
                println("WebSocket disconnected")
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.address.size == 4) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private suspend fun handleMessage(text: String) {
        // Try to parse as different event types

        // User task created
        runCatching {
            json.decodeFromString<UserTaskCreatedEventDto>(text)
        }.onSuccess {
            _userTaskEvents.emit(it)
            return
        }

        // User task cancelled
        runCatching {
            json.decodeFromString<UserTaskCancelledEventDto>(text)
        }.onSuccess {
            _userTaskCancelledEvents.emit(it)
            return
        }

        // Error notification
        runCatching {
            json.decodeFromString<ErrorNotificationEventDto>(text)
        }.onSuccess {
            _errorEvents.emit(it)
            return
        }

        // Debug events
        runCatching {
            json.decodeFromString<DebugEventDto>(text)
        }.onSuccess {
            _debugEvents.emit(it)
            return
        }

        println("Unknown WebSocket message: $text")
    }

    /**
     * Stop WebSocket connection
     */
    fun stop() {
        isRunning = false
        scope.cancel()
        client.close()
    }
}
