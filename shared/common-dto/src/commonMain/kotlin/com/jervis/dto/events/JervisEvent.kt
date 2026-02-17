package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
sealed class JervisEvent {
    abstract val timestamp: String

    @Serializable
    data class UserTaskCreated(
        val clientId: String,
        val taskId: String,
        val title: String,
        override val timestamp: String,
        // Approval metadata (backward compatible – CBOR defaults)
        val interruptAction: String? = null,
        val interruptDescription: String? = null,
        val isApproval: Boolean = false,
        val projectId: String? = null,
    ) : JervisEvent()

    @Serializable
    data class UserTaskCancelled(
        val clientId: String,
        val taskId: String,
        val title: String,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class ErrorNotification(
        val id: String,
        val severity: String,
        val message: String,
        val clientId: String? = null,
        val projectId: String? = null,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class PendingTaskCreated(
        val taskId: String,
        val type: String,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class MeetingStateChanged(
        val meetingId: String,
        val clientId: String,
        val newState: String,
        val title: String? = null,
        val errorMessage: String? = null,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class MeetingTranscriptionProgress(
        val meetingId: String,
        val clientId: String,
        val percent: Double,
        val segmentsDone: Int,
        val elapsedSeconds: Double,
        val lastSegmentText: String? = null,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class MeetingCorrectionProgress(
        val meetingId: String,
        val clientId: String,
        val percent: Double,
        val chunksDone: Int,
        val totalChunks: Int,
        val message: String? = null,
        val tokensGenerated: Int = 0,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class QualificationProgress(
        val taskId: String,
        val clientId: String,
        val message: String,
        val step: String,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class OrchestratorTaskProgress(
        val taskId: String,
        val clientId: String,
        val node: String,
        val message: String,
        val percent: Double = 0.0,
        val goalIndex: Int = 0,
        val totalGoals: Int = 0,
        val stepIndex: Int = 0,
        val totalSteps: Int = 0,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class OrchestratorTaskStatusChange(
        val taskId: String,
        val clientId: String = "",
        val threadId: String,
        val status: String,
        val summary: String? = null,
        val error: String? = null,
        val interruptAction: String? = null,
        val interruptDescription: String? = null,
        val branch: String? = null,
        val artifacts: List<String> = emptyList(),
        override val timestamp: String,
    ) : JervisEvent()
}
