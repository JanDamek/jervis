package com.jervis.dto.error

import kotlinx.serialization.Serializable

@Serializable
data class ErrorLogCreateRequestDto(
    val clientId: String? = null,
    val projectId: String? = null,
    val correlationId: String? = null,
    val message: String,
    val stackTrace: String? = null,
    val causeType: String? = null,
)
