package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.dto.BaseChoice

data class CompletionChoice(
    val text: String,
    override val index: Int,
    val logprobs: Any? = null,
    @JsonProperty("finish_reason")
    override val finishReason: String = "stop",
) : BaseChoice(index, finishReason)
