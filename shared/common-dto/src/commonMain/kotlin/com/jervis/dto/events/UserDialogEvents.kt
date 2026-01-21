package com.jervis.dto.events

import kotlinx.serialization.Serializable

/**
 * Event for sending response to a user dialog request.
 * Client → Server: UserDialogResponseEventDto (answer)
 */

@Serializable
data class UserDialogResponseEventDto(
    val type: String = "USER_DIALOG_RESPONSE",
    val dialogId: String,
    val correlationId: String,
    val answer: String,
    val accepted: Boolean = true,
    val timestamp: String,
)
