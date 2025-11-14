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

/**
 * Extension functions for DebugEventDto to get event name and details for TaskFlowEvent
 */
fun DebugEventDto.getEventName(): String? = when (this) {
    is DebugEventDto.TaskCreated -> "TaskCreated"
    is DebugEventDto.TaskStateTransition -> "TaskStateTransition"
    is DebugEventDto.QualificationStart -> "QualificationStart"
    is DebugEventDto.QualificationDecision -> "QualificationDecision"
    is DebugEventDto.GpuTaskPickup -> "GpuTaskPickup"
    is DebugEventDto.PlanCreated -> "PlanCreated"
    is DebugEventDto.PlanStatusChanged -> "PlanStatusChanged"
    is DebugEventDto.PlanStepAdded -> "PlanStepAdded"
    is DebugEventDto.StepExecutionStart -> "StepExecutionStart"
    is DebugEventDto.StepExecutionCompleted -> "StepExecutionCompleted"
    is DebugEventDto.FinalizerStart -> "FinalizerStart"
    is DebugEventDto.FinalizerComplete -> "FinalizerComplete"
    is DebugEventDto.IndexingStart -> "IndexingStart"
    is DebugEventDto.EmbeddingGenerated -> "EmbeddingGenerated"
    is DebugEventDto.VectorStored -> "VectorStored"
    is DebugEventDto.IndexingCompleted -> "IndexingCompleted"
    is DebugEventDto.TextExtracted -> "TextExtracted"
    is DebugEventDto.TextNormalized -> "TextNormalized"
    is DebugEventDto.Chunked -> "Chunked"
    is DebugEventDto.ChunkStored -> "ChunkStored"
    is DebugEventDto.ExternalDownloadStart -> "ExternalDownloadStart"
    is DebugEventDto.ExternalDownloadCompleted -> "ExternalDownloadCompleted"
    is DebugEventDto.ExternalDownloadFailed -> "ExternalDownloadFailed"
    else -> null // SessionStarted, ResponseChunk, SessionCompleted handled separately
}

fun DebugEventDto.getEventDetails(): String? = when (this) {
    is DebugEventDto.TaskCreated ->
        "Task $taskId created (type: $taskType, state: $state)"
    is DebugEventDto.TaskStateTransition ->
        "Task $taskId: $fromState → $toState"
    is DebugEventDto.QualificationStart ->
        "Started qualification for task $taskId (type: $taskType)"
    is DebugEventDto.QualificationDecision ->
        "Decision: $decision (duration: ${duration}ms)\nReason: $reason"
    is DebugEventDto.GpuTaskPickup ->
        "GPU picked up task $taskId (type: $taskType, state: $state)"
    is DebugEventDto.PlanCreated ->
        "Plan $planId created\nInstruction: $taskInstruction\nBackground: $backgroundMode"
    is DebugEventDto.PlanStatusChanged ->
        "Plan $planId status changed to: $status"
    is DebugEventDto.PlanStepAdded ->
        "Step #$order added to plan $planId\nTool: $toolName\nInstruction: $instruction"
    is DebugEventDto.StepExecutionStart ->
        "Started step #$order in plan $planId\nTool: $toolName"
    is DebugEventDto.StepExecutionCompleted ->
        "Completed step in plan $planId\nTool: $toolName\nStatus: $status\nResult type: $resultType"
    is DebugEventDto.FinalizerStart ->
        "Finalizer started for plan $planId\nTotal: $totalSteps, Completed: $completedSteps, Failed: $failedSteps"
    is DebugEventDto.FinalizerComplete ->
        "Finalizer completed for plan $planId\nAnswer length: $answerLength"
    is DebugEventDto.IndexingStart ->
        "Indexing started\nSource: $sourceType\nModel: $modelType\nURI: ${sourceUri ?: "N/A"}\nText length: $textLength"
    is DebugEventDto.EmbeddingGenerated ->
        "Embedding generated\nModel: $modelType\nDimension: $vectorDim\nText length: $textLength"
    is DebugEventDto.VectorStored ->
        "Vector stored\nModel: $modelType\nStore ID: $vectorStoreId"
    is DebugEventDto.IndexingCompleted ->
        "Indexing completed\nSuccess: $success${errorMessage?.let { "\nError: $it" } ?: ""}"
    is DebugEventDto.TextExtracted ->
        "Text extracted\nExtractor: $extractor\nLength: $length"
    is DebugEventDto.TextNormalized ->
        "Text normalized\nLength: $length"
    is DebugEventDto.Chunked ->
        "Text chunked into $chunks chunks"
    is DebugEventDto.ChunkStored ->
        "Chunk stored\nIndex: $index/$total\nStore ID: $vectorStoreId"
    is DebugEventDto.ExternalDownloadStart ->
        "Download started\nURL: $url"
    is DebugEventDto.ExternalDownloadCompleted ->
        "Download completed\nURL: $url\nType: ${contentType ?: "unknown"}\nSize: $bytes bytes"
    is DebugEventDto.ExternalDownloadFailed ->
        "Download failed\nURL: $url\nReason: $reason"
    else -> null
}

fun DebugEventDto.getCorrelationId(): String? = when (this) {
    is DebugEventDto.SessionStarted -> correlationId
    is DebugEventDto.TaskCreated -> correlationId
    is DebugEventDto.TaskStateTransition -> correlationId
    is DebugEventDto.QualificationStart -> correlationId
    is DebugEventDto.QualificationDecision -> correlationId
    is DebugEventDto.GpuTaskPickup -> correlationId
    is DebugEventDto.PlanCreated -> correlationId
    is DebugEventDto.PlanStatusChanged -> correlationId
    is DebugEventDto.PlanStepAdded -> correlationId
    is DebugEventDto.StepExecutionStart -> correlationId
    is DebugEventDto.StepExecutionCompleted -> correlationId
    is DebugEventDto.FinalizerStart -> correlationId
    is DebugEventDto.FinalizerComplete -> correlationId
    is DebugEventDto.IndexingStart -> correlationId
    is DebugEventDto.EmbeddingGenerated -> correlationId
    is DebugEventDto.VectorStored -> correlationId
    is DebugEventDto.IndexingCompleted -> correlationId
    is DebugEventDto.TextExtracted -> correlationId
    is DebugEventDto.TextNormalized -> correlationId
    is DebugEventDto.Chunked -> correlationId
    is DebugEventDto.ChunkStored -> correlationId
    is DebugEventDto.ExternalDownloadStart -> correlationId
    is DebugEventDto.ExternalDownloadCompleted -> correlationId
    is DebugEventDto.ExternalDownloadFailed -> correlationId
    else -> null
}
