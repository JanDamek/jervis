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
    data class UserDialogRequest(
        val dialogId: String,
        val correlationId: String,
        val clientId: String,
        val projectId: String? = null,
        val question: String,
        val proposedAnswer: String? = null,
        override val timestamp: String,
    ) : JervisEvent()

    @Serializable
    data class UserDialogClose(
        val dialogId: String,
        val correlationId: String,
        val reason: String = "CLOSED",
        override val timestamp: String,
    ) : JervisEvent()
}
