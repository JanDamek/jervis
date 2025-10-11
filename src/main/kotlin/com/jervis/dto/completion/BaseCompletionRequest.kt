package com.jervis.dto.completion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Base class for completion requests */
@Serializable
open class BaseCompletionRequest(
    open val model: String? = null,
    open val temperature: Double? = null,
    @SerialName("max_tokens")
    open val maxTokens: Int? = null,
    open val stream: Boolean? = null,
)
