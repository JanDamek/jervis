package com.jervis.dto.completion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CompletionChoice(
    val text: String,
    val index: Int,
    val logprobs: String? = null,
    @SerialName("finish_reason")
    val finishReason: String = "stop",
)
