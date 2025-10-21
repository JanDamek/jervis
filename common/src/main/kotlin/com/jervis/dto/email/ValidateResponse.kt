package com.jervis.dto.email

import kotlinx.serialization.Serializable

@Serializable
data class ValidateResponse(
    val ok: Boolean,
    val message: String? = null,
)
