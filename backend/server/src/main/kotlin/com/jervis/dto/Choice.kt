package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.dto.chat.ChatMessageDto

data class Choice(
    val index: Int,
    val message: ChatMessageDto,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)
