package com.jervis.dto.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ResponseChunk")
data class ResponseChunkDto(
    val sessionId: String,
    val chunk: String,
) : DebugEventDto()
