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
        override val timestamp: String,
    ) : JervisEvent()
}
