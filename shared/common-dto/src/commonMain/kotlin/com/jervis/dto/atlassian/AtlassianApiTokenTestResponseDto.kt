package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianApiTokenTestResponseDto(
    val valid: Boolean,
    val message: String? = null,
    val tenant: String? = null,
    val email: String? = null,
)
