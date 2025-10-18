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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DebugWebSocketClient(
    private val baseUrl: String,
    private val debugWindowService: IDebugWindowService,
) {
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

    fun start() {
        val wsUrl =
            baseUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://")
                .plus("/ws/debug")

        scope.launch {
            client.webSocket(wsUrl) {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handleMessage(text)
                }
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        runCatching {
            when (val event = json.decodeFromString<DebugEventDto>(text)) {
                is DebugEventDto.SessionStarted -> {
                    debugWindowService.startDebugSession(
                        sessionId = event.sessionId,
                        promptType = event.promptType,
                        systemPrompt = event.systemPrompt,
                        userPrompt = event.userPrompt,
                    )
                }

                is DebugEventDto.ResponseChunk -> {
                    debugWindowService.appendResponse(
                        sessionId = event.sessionId,
                        chunk = event.chunk,
                    )
                }

                is DebugEventDto.SessionCompleted -> {
                    debugWindowService.completeSession(
                        sessionId = event.sessionId,
                    )
                }
            }
        }.onFailure {
            println("Failed to handle debug message: ${it.message}")
        }
    }

    fun stop() {
        scope.cancel()
        client.close()
    }
}
