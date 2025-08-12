package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty

// Base class for completion requests
open class BaseCompletionRequest(
    open val model: String? = null,
    open val temperature: Double? = null,
    @JsonProperty("max_tokens")
    open val maxTokens: Int? = null,
    open val stream: Boolean? = null,
)
