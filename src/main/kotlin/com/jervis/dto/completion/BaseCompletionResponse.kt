package com.jervis.dto.completion

import com.jervis.dto.Usage
import kotlinx.serialization.Serializable

/** Base class for completion responses */
@Serializable
open class BaseCompletionResponse(
    open val id: String,
    open val `object`: String,
    open val created: Long,
    open val model: String,
    open val usage: Usage?,
)
