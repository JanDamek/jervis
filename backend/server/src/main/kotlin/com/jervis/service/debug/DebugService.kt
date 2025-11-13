package com.jervis.service.debug

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.dto.events.DebugEventDto
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
        taskId: String,
        fromState: String,
        toState: String,
        taskType: String,
    ) {
        val dto =
            DebugEventDto.TaskStateTransition(
                correlationId = correlationId,
                taskId = taskId,
                fromState = fromState,
                toState = toState,
                taskType = taskType,
            )
        broadcast(dto)
    }

    fun qualificationStart(
        correlationId: String,
        taskId: String,
        taskType: String,
    ) {
        val dto =
            DebugEventDto.QualificationStart(
                correlationId = correlationId,
                taskId = taskId,
                taskType = taskType,
            )
        broadcast(dto)
    }

    fun qualificationDecision(
        correlationId: String,
        taskId: String,
        decision: String,
        duration: Long,
        reason: String,
    ) {
        val dto =
            DebugEventDto.QualificationDecision(
                correlationId = correlationId,
                taskId = taskId,
                decision = decision,
                duration = duration,
                reason = reason,
            )
        broadcast(dto)
    }

    fun gpuTaskPickup(
        correlationId: String,
        taskId: String,
        taskType: String,
        state: String,
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

    fun planCreated(
        correlationId: String,
        planId: String,
        taskId: String?,
        taskInstruction: String,
        backgroundMode: Boolean,
    ) {
        val dto =
            DebugEventDto.PlanCreated(
                correlationId = correlationId,
                planId = planId,
                taskId = taskId,
                taskInstruction = taskInstruction,
                backgroundMode = backgroundMode,
            )
        broadcast(dto)
    }

    fun planStatusChanged(
        correlationId: String,
        planId: String,
        status: String,
    ) {
        val dto =
            DebugEventDto.PlanStatusChanged(
                correlationId = correlationId,
                planId = planId,
                status = status,
            )
        broadcast(dto)
    }

    fun planStepAdded(
        correlationId: String,
        planId: String,
        stepId: String,
        toolName: String,
        instruction: String,
        order: Int,
    ) {
        val dto =
            DebugEventDto.PlanStepAdded(
                correlationId = correlationId,
                planId = planId,
                stepId = stepId,
                toolName = toolName,
                instruction = instruction,
                order = order,
            )
        broadcast(dto)
    }

    fun stepExecutionStart(
        correlationId: String,
        planId: String,
        stepId: String,
        toolName: String,
        order: Int,
    ) {
        val dto =
            DebugEventDto.StepExecutionStart(
                correlationId = correlationId,
                planId = planId,
                stepId = stepId,
                toolName = toolName,
                order = order,
            )
        broadcast(dto)
    }

    fun stepExecutionCompleted(
        correlationId: String,
        planId: String,
        stepId: String,
        toolName: String,
        status: String,
        resultType: String,
    ) {
        val dto =
            DebugEventDto.StepExecutionCompleted(
                correlationId = correlationId,
                planId = planId,
                stepId = stepId,
                toolName = toolName,
                status = status,
                resultType = resultType,
            )
        broadcast(dto)
    }

    fun finalizerStart(
        correlationId: String,
        planId: String,
        totalSteps: Int,
        completedSteps: Int,
        failedSteps: Int,
    ) {
        val dto =
            DebugEventDto.FinalizerStart(
                correlationId = correlationId,
                planId = planId,
                totalSteps = totalSteps,
                completedSteps = completedSteps,
                failedSteps = failedSteps,
            )
        broadcast(dto)
    }

    fun finalizerComplete(
        correlationId: String,
        planId: String,
        answerLength: Int,
    ) {
        val dto =
            DebugEventDto.FinalizerComplete(
                correlationId = correlationId,
                planId = planId,
                answerLength = answerLength,
            )
        broadcast(dto)
    }

    private fun broadcast(dto: DebugEventDto) {
        val jsonString = json.encodeToString<DebugEventDto>(dto)
        sessionManager.broadcastToChannel(jsonString, WebSocketChannelTypeEnum.DEBUG)
    }
}
