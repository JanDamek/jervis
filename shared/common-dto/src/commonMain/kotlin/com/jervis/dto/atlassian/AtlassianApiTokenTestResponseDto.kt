package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianApiTokenTestResponseDto(
    val success: Boolean,
    val message: String? = null,
)
