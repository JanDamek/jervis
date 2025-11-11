package com.jervis.desktop

import com.jervis.dto.events.DebugEventDto
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
 * Separate WebSocket client for debug events (LLM calls)
 */
class DebugWebSocketClient(private val serverBaseUrl: String) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { 
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

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

        client.webSocket(wsUrl) {
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
        isRunning = false
        scope.cancel()
        client.close()
    }
}
