package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class Choice(
    override val index: Int,
    val message: ChatMessage,
    @JsonProperty("finish_reason")
    override val finishReason: String?,
) : BaseChoice(index, finishReason)
