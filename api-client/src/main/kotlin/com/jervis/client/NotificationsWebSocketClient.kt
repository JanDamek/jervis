package com.jervis.client

import com.jervis.dto.events.PlanStatusChangeEventDto
import com.jervis.dto.events.StepCompletionEventDto
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.springframework.context.ApplicationEventPublisher

class NotificationsWebSocketClient(
    private val baseUrl: String,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        HttpClient(CIO) {
            install(WebSockets)
        }

    fun start() {
        val wsUrl =
            baseUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://")
                .plus("/ws/notifications")

        scope.launch {
            client.webSocket(wsUrl) {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handleMessage(text)
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        runCatching {
            json.decodeFromString(StepCompletionEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
            return
        }

        runCatching {
            json.decodeFromString(PlanStatusChangeEventDto.serializer(), text)
        }.onSuccess {
            eventPublisher.publishEvent(it)
        }
    }

    fun stop() {
        scope.cancel()
        client.close()
    }
}
