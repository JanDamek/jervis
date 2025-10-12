package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty

data class CompletionRequest(
    override val model: String? = null,
    val prompt: String = "",
    override val temperature: Double? = null,
    @JsonProperty("max_tokens")
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)
