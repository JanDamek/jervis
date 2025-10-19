package com.jervis.client

import com.jervis.dto.events.DebugEventDto
import com.jervis.service.IDebugWindowService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import kotlin.math.min

class DebugWebSocketClient(
    private val baseUrl: String,
    private val debugWindowService: IDebugWindowService,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

    private val client =
        HttpClient(CIO) {
            install(WebSockets)
        }

    @Volatile
    private var isRunning = false

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
                    val message =
                        when (e) {
                            is java.net.ConnectException -> "Server not available (${e.message})"
                            else -> e.message ?: "Unknown error"
                        }
                    logger.warn { "Debug WebSocket connection failed: $message. Reconnecting in ${reconnectDelay}ms..." }
                    delay(reconnectDelay)
                    reconnectDelay = min(reconnectDelay * 2, maxReconnectDelay)
                }
            }
        }
    }

    private suspend fun connect() {
        val wsUrl =
            baseUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://")
                .plus("/ws/debug")

        client.webSocket(wsUrl) {
            logger.info { "Debug WebSocket connected" }
            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handleMessage(text)
                }
            } finally {
                logger.info { "Debug WebSocket disconnected" }
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        runCatching {
            logger.debug { "Received debug message: ${text.take(100)}..." }
            when (val event = json.decodeFromString<DebugEventDto>(text)) {
                is DebugEventDto.SessionStarted -> {
                    logger.info {
                        "Debug session started: ${event.sessionId} (${event.promptType}) for client: ${event.clientName ?: "System"}"
                    }
                    debugWindowService.startDebugSession(
                        sessionId = event.sessionId,
                        promptType = event.promptType,
                        systemPrompt = event.systemPrompt,
                        userPrompt = event.userPrompt,
                        clientId = event.clientId,
                        clientName = event.clientName,
                    )
                }

                is DebugEventDto.ResponseChunkDto -> {
                    debugWindowService.appendResponse(
                        sessionId = event.sessionId,
                        chunk = event.chunk,
                    )
                }

                is DebugEventDto.SessionCompletedDto -> {
                    logger.info { "Debug session completed: ${event.sessionId}" }
                    debugWindowService.completeSession(
                        sessionId = event.sessionId,
                    )
                }
            }
        }.onFailure {
            logger.error(it) { "Failed to handle debug message: ${text.take(200)}" }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        client.close()
    }
}
