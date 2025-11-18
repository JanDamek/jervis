package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

// Lightweight list DTOs for UI selection
@Serializable
data class AtlassianProjectRefDto(
    val key: String,
    val name: String,
)
