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
        // Error mode — task failed, show error detail + retry/discard
        val isError: Boolean = false,
        val errorDetail: String? = null,
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
        val metadata: Map<String, String> = emptyMap(),
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

    /**
     * Memory graph changed — triggers UI refresh of the Paměťový graf panel.
     * Emitted when vertex status changes during background task execution.
     */
    @Serializable
    data class MemoryGraphChanged(
        override val timestamp: String,
    ) : JervisEvent()

    /**
     * Connection state changed — emitted when connection discovery completes
     * or connection state changes significantly. Shown as snackbar in UI.
     */
    @Serializable
    data class ConnectionStateChanged(
        val connectionId: String,
        val connectionName: String,
        val newState: String,
        val message: String,
        override val timestamp: String,
    ) : JervisEvent()

    /**
     * EPIC 5: Approval required event — emitted when an action needs user approval.
     */
    @Serializable
    data class ApprovalRequired(
        val clientId: String,
        val taskId: String,
        val action: String,
        val preview: String,
        val context: String = "",
        val riskLevel: String = "MEDIUM",
        override val timestamp: String,
    ) : JervisEvent()

    /**
     * Meeting recording trigger — server-side MeetingRecordingDispatcher emits this
     * to the approved client's desktop when an approved CALENDAR_PROCESSING task
     * enters its start window. The desktop recorder then spawns a loopback capture
     * (ffmpeg) and streams audio chunks to MeetingRpc.uploadAudioChunk.
     *
     * Read-only v1: NO auto-join, NO disclaimer messages — the user is expected to
     * join the meeting themselves; Jervis only captures the loopback audio their
     * device is already playing.
     */
    @Serializable
    data class MeetingRecordingTrigger(
        val taskId: String,
        val clientId: String,
        val projectId: String? = null,
        val title: String,
        val startTime: String,
        val endTime: String,
        val provider: String,           // "MICROSOFT_TEAMS", "GOOGLE_MEET", "ZOOM", ...
        val joinUrl: String? = null,
        override val timestamp: String,
    ) : JervisEvent()

    /**
     * Meeting recording stop — emitted when the user denies (after approve) or
     * when the dispatcher detects the scheduled end window has passed. Desktop
     * recorder terminates ffmpeg and calls MeetingRpc.finalizeRecording.
     */
    @Serializable
    data class MeetingRecordingStop(
        val taskId: String,
        val clientId: String,
        val reason: String,             // "END_TIME", "USER_STOP", "CANCELLED"
        override val timestamp: String,
    ) : JervisEvent()

    /**
     * Meeting helper message — real-time translation, suggestion, or Q&A prediction
     * pushed to the device during an active meeting helper session.
     */
    @Serializable
    data class MeetingHelperMessage(
        val meetingId: String,
        val type: String,          // "translation", "suggestion", "question_predict", "status"
        val text: String,
        val context: String = "",
        val fromLang: String = "",
        val toLang: String = "",
        override val timestamp: String,
    ) : JervisEvent()
}
