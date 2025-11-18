package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianProjectSelectionDto(
    val clientId: String,
    val projectKey: String,
)
