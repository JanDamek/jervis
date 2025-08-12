package com.jervis.dto.completion

import com.jervis.dto.Usage

// Base class for completion responses
open class BaseCompletionResponse(
    open val id: String,
    open val `object`: String,
    open val created: Long,
    open val model: String,
    open val usage: Usage?,
)
