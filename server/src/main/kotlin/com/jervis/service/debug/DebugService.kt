package com.jervis.service.debug

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.dto.events.DebugEventDto
import com.jervis.dto.events.ResponseChunkDto
import com.jervis.dto.events.SessionCompletedDto
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Debug service that broadcasts debug events to debug WebSocket channel.
 * Uses WebSocketSessionManager with channel-based routing.
 */
@Service
class DebugService(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json =
        Json {
            encodeDefaults = true
            prettyPrint = false
            classDiscriminator = "type"
        }

    fun sessionStarted(
        sessionId: String,
        promptType: String,
        systemPrompt: String,
        userPrompt: String,
        clientId: String? = null,
        clientName: String? = null,
    ) {
        val dto: DebugEventDto =
            DebugEventDto.SessionStarted(
                sessionId = sessionId,
                promptType = promptType,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                clientId = clientId,
                clientName = clientName,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        logger.debug { "Broadcasting debug session started: $sessionId" }
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }

    fun responseChunk(
        sessionId: String,
        chunk: String,
    ) {
        val dto: DebugEventDto =
            ResponseChunkDto(
                sessionId = sessionId,
                chunk = chunk,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }

    fun sessionCompleted(sessionId: String) {
        val dto: DebugEventDto =
            SessionCompletedDto(
                sessionId = sessionId,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        logger.debug { "Broadcasting debug session completed: $sessionId" }
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }
}
