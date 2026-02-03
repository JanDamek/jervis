package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianProjectRefDto(
    val key: String,
    val name: String,
    val description: String? = null,
)
