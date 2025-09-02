package com.jervis.dto

// Base class for choices in completion responses
open class BaseChoice(
    open val index: Int,
    open val finishReason: String?,
)
