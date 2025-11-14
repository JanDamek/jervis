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
        val correlationId: String? = null, // For grouping related LLM sessions
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

    // Indexing pipeline events
    @Serializable
    @SerialName("IndexingStart")
    data class IndexingStart(
        val correlationId: String,
        val sourceType: String,
        val modelType: String,
        val sourceUri: String? = null,
        val textLength: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("EmbeddingGenerated")
    data class EmbeddingGenerated(
        val correlationId: String,
        val modelType: String,
        val vectorDim: Int,
        val textLength: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("VectorStored")
    data class VectorStored(
        val correlationId: String,
        val modelType: String,
        val vectorStoreId: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("IndexingCompleted")
    data class IndexingCompleted(
        val correlationId: String,
        val success: Boolean,
        val errorMessage: String? = null,
    ) : DebugEventDto()

    @Serializable
    @SerialName("TextExtracted")
    data class TextExtracted(
        val correlationId: String,
        val extractor: String,
        val length: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("TextNormalized")
    data class TextNormalized(
        val correlationId: String,
        val length: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("Chunked")
    data class Chunked(
        val correlationId: String,
        val chunks: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("ChunkStored")
    data class ChunkStored(
        val correlationId: String,
        val index: Int,
        val total: Int,
        val vectorStoreId: String,
    ) : DebugEventDto()

    // External download events (links, attachments)
    @Serializable
    @SerialName("ExternalDownloadStart")
    data class ExternalDownloadStart(
        val correlationId: String,
        val url: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("ExternalDownloadCompleted")
    data class ExternalDownloadCompleted(
        val correlationId: String,
        val url: String,
        val contentType: String?,
        val bytes: Int,
    ) : DebugEventDto()

    @Serializable
    @SerialName("ExternalDownloadFailed")
    data class ExternalDownloadFailed(
        val correlationId: String,
        val url: String,
        val reason: String,
    ) : DebugEventDto()
}
