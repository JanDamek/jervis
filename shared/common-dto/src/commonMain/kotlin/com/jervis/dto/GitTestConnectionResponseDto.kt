package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTO returned by Git test connection endpoint.
 * Replaces ambiguous Map<String, Any> to be compatible with kotlinx.serialization.
 */
@Serializable
data class GitTestConnectionResponseDto(
    val success: Boolean,
    val message: String? = null,
)
