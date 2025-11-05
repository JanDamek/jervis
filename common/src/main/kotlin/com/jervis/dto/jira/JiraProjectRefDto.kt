package com.jervis.dto.jira

import kotlinx.serialization.Serializable

// Lightweight list DTOs for UI selection
@Serializable
data class JiraProjectRefDto(
    val key: String,
    val name: String,
)
