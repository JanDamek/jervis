package com.jervis.dto.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DebugEventDto {
    @Serializable
    @SerialName("SessionStarted")
    data class SessionStarted(
        val sessionId: String,
        val promptType: String,
        val systemPrompt: String,
        val userPrompt: String,
        val clientId: String? = null,
        val clientName: String? = null,
    ) : DebugEventDto()

    @Serializable
    @SerialName("ResponseChunk")
    data class ResponseChunkDto(
        val sessionId: String,
        val chunk: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("SessionCompleted")
    data class SessionCompletedDto(
        val sessionId: String,
    ) : DebugEventDto()

    // Task stream events - all events have correlationId for distributed tracing & UI grouping
    @Serializable
    @SerialName("TaskCreated")
    data class TaskCreated(
        val correlationId: String, // Tracks entire execution flow across all services
        val taskId: String,
        val taskType: String,
        val state: String,
        val clientId: String,
        val projectId: String?,
        val contentLength: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("TaskStateTransition")
    data class TaskStateTransition(
        val correlationId: String,
        val taskId: String,
        val fromState: String,
        val toState: String,
        val taskType: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("QualificationStart")
    data class QualificationStart(
        val correlationId: String,
        val taskId: String,
        val taskType: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("QualificationDecision")
    data class QualificationDecision(
        val correlationId: String,
        val taskId: String,
        val decision: String, // DISCARD or DELEGATE
        val duration: Long,
        val reason: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("GpuTaskPickup")
    data class GpuTaskPickup(
        val correlationId: String,
        val taskId: String,
        val taskType: String,
        val state: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("PlanCreated")
    data class PlanCreated(
        val correlationId: String,
        val planId: String,
        val taskId: String?,
        val taskInstruction: String,
        val backgroundMode: Boolean,
    ) : DebugEventDto()

    @Serializable
    @SerialName("PlanStatusChanged")
    data class PlanStatusChanged(
        val correlationId: String,
        val planId: String,
        val status: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("PlanStepAdded")
    data class PlanStepAdded(
        val correlationId: String,
        val planId: String,
        val stepId: String,
        val toolName: String,
        val instruction: String,
        val order: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("StepExecutionStart")
    data class StepExecutionStart(
        val correlationId: String,
        val planId: String,
        val stepId: String,
        val toolName: String,
        val order: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("StepExecutionCompleted")
    data class StepExecutionCompleted(
        val correlationId: String,
        val planId: String,
        val stepId: String,
        val toolName: String,
        val status: String, // DONE, FAILED, etc.
        val resultType: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("FinalizerStart")
    data class FinalizerStart(
        val correlationId: String,
        val planId: String,
        val totalSteps: Int,
        val completedSteps: Int,
        val failedSteps: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("FinalizerComplete")
    data class FinalizerComplete(
        val correlationId: String,
        val planId: String,
        val answerLength: Int,
    ) : DebugEventDto()
}
