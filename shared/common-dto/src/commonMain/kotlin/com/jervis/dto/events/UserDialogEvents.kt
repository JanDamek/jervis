package com.jervis.dto.events

import kotlinx.serialization.Serializable

/**
 * WebSocket events for interactive User Dialogs.
 * Server → Clients: UserDialogRequestEventDto, UserDialogCloseEventDto
 * Client → Server: UserDialogResponseEventDto (answer), UserDialogCloseEventDto (cancel/close)
 */

@Serializable
data class UserDialogRequestEventDto(
    val type: String = "USER_DIALOG_REQUEST",
    val dialogId: String,
    val correlationId: String,
    val clientId: String,
    val projectId: String? = null,
    val question: String,
    val proposedAnswer: String? = null,
    val timestamp: String,
)

@Serializable
data class UserDialogResponseEventDto(
    val type: String = "USER_DIALOG_RESPONSE",
    val dialogId: String,
    val correlationId: String,
    val answer: String,
    val accepted: Boolean = true,
    val timestamp: String,
)

@Serializable
data class UserDialogCloseEventDto(
    val type: String = "USER_DIALOG_CLOSE",
    val dialogId: String,
    val correlationId: String,
    val reason: String = "CLOSED",
    val timestamp: String,
)
