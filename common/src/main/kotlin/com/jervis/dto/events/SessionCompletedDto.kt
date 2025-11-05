package com.jervis.dto.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("SessionCompleted")
data class SessionCompletedDto(
    val sessionId: String,
) : DebugEventDto()
