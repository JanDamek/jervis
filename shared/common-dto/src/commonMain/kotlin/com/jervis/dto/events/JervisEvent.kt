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
}
