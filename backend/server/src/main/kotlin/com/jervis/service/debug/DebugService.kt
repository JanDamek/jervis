package com.jervis.service.debug

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.events.DebugEventDto
import com.jervis.service.websocket.WebSocketSessionManager
import com.jervis.types.TaskId
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
        correlationId: String,
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
                correlationId = correlationId,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        logger.debug { "Broadcasting debug session started: $sessionId with correlationId: $correlationId" }
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }

    fun responseChunk(
        sessionId: String,
        chunk: String,
    ) {
        val dto: DebugEventDto =
            DebugEventDto.ResponseChunkDto(
                sessionId = sessionId,
                chunk = chunk,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }

    fun sessionCompleted(sessionId: String) {
        val dto: DebugEventDto =
            DebugEventDto.SessionCompletedDto(
                sessionId = sessionId,
            )
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        logger.debug { "Broadcasting debug session completed: $sessionId" }
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }

    // Task stream events - all require correlationId for grouping
    fun taskCreated(
        correlationId: String,
        taskId: String,
        taskType: String,
        state: String,
        clientId: String,
        projectId: String?,
        contentLength: Int,
    ) {
        val dto =
            DebugEventDto.TaskCreated(
                correlationId = correlationId,
                taskId = taskId,
                taskType = taskType,
                state = state,
                clientId = clientId,
                projectId = projectId,
                contentLength = contentLength,
            )
        broadcast(dto)
    }

    fun taskStateTransition(
        correlationId: String,
        taskId: TaskId,
        fromState: String,
        toState: String,
        taskType: TaskTypeEnum,
    ) {
        val dto =
            DebugEventDto.TaskStateTransition(
                correlationId = correlationId,
                taskId = taskId.toString(),
                fromState = fromState,
                toState = toState,
                taskType = taskType,
            )
        broadcast(dto)
    }

    fun gpuTaskPickup(
        correlationId: String,
        taskId: String,
        taskType: TaskTypeEnum,
        state: TaskStateEnum,
    ) {
        val dto =
            DebugEventDto.GpuTaskPickup(
                correlationId = correlationId,
                taskId = taskId,
                taskType = taskType,
                state = state,
            )
        broadcast(dto)
    }

    private fun broadcast(dto: DebugEventDto) {
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        // Broadcast to a dedicated DEBUG channel
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
        // Also mirror to NOTIFICATIONS channel so Desktop (primary platform) can display
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.NOTIFICATIONS)
    }
}
