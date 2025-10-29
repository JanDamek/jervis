package com.jervis.dto.email

import kotlinx.serialization.Serializable

@Serializable
data class ValidateResponseDto(
    val ok: Boolean,
    val message: String? = null,
)
