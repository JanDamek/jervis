package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty

// Base class for choices in completion responses
open class BaseChoice(
    open val index: Int,
    @JsonProperty("finish_reason")
    open val finishReason: String?,
)
