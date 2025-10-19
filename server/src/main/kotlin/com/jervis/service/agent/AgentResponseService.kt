package com.jervis.service.agent

import com.jervis.dto.events.AgentResponseEventDto
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Service for sending targeted agent responses to specific WebSocket clients.
 */
@Service
class AgentResponseService(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json = Json { encodeDefaults = true }

    fun sendAgentResponse(
        wsSessionId: String,
        contextId: String,
        message: String,
        status: String,
    ) {
        val dto =
            AgentResponseEventDto(
                wsSessionId = wsSessionId,
                contextId = contextId,
                message = message,
                status = status,
                timestamp = LocalDateTime.now().toString(),
            )
        sessionManager.sendToSession(wsSessionId, json.encodeToString(dto))
    }

    fun sendAgentStarted(
        wsSessionId: String,
        contextId: String,
    ) {
        sendAgentResponse(wsSessionId, contextId, "Agent processing started", "STARTED")
    }

    fun sendAgentProcessing(
        wsSessionId: String,
        contextId: String,
        message: String,
    ) {
        sendAgentResponse(wsSessionId, contextId, message, "PROCESSING")
    }

    fun sendAgentCompleted(
        wsSessionId: String,
        contextId: String,
        message: String,
    ) {
        sendAgentResponse(wsSessionId, contextId, message, "COMPLETED")
    }

    fun sendAgentFailed(
        wsSessionId: String,
        contextId: String,
        error: String,
    ) {
        sendAgentResponse(wsSessionId, contextId, error, "FAILED")
    }
}
