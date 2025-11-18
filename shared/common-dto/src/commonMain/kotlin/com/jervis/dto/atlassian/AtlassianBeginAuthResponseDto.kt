package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianBeginAuthResponseDto(
    val correlationId: String,
    val authUrl: String,
)
