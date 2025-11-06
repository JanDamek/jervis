package com.jervis.client

import com.jervis.dto.events.AgentResponseEventDto
import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.dto.events.JiraAuthPromptEventDto
import com.jervis.dto.events.PlanStatusChangeEventDto
import com.jervis.dto.events.StepCompletionEventDto
import com.jervis.dto.events.UserTaskCreatedEventDto
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
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.math.min

class NotificationsWebSocketClient(
    private val baseUrl: String,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    val sessionId: String = UUID.randomUUID().toString()

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
                    logger.warn { "WebSocket connection failed: $message. Reconnecting in ${reconnectDelay}ms..." }
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
                .plus("/ws/notifications")

        client.webSocket(wsUrl) {
            logger.info { "WebSocket connected with session ID: $sessionId" }
            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handleMessage(text)
                }
            } finally {
                logger.info { "WebSocket disconnected" }
            }
        }
    }

    private fun handleMessage(text: String) {
        // Try to decode as StepCompletionEventDto
        runCatching {
            json.decodeFromString(StepCompletionEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        // Try to decode as PlanStatusChangeEventDto
        runCatching {
            json.decodeFromString(PlanStatusChangeEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        // Try to decode as AgentResponseEventDto
        runCatching {
            json.decodeFromString(AgentResponseEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        // Try to decode as JiraAuthPromptEventDto
        runCatching {
            json.decodeFromString(JiraAuthPromptEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        // Try to decode as ErrorNotificationEventDto
        runCatching {
            json.decodeFromString(ErrorNotificationEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        // Try to decode as UserTaskCreatedEventDto
        runCatching {
            json.decodeFromString(UserTaskCreatedEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        client.close()
    }
}
