package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraApiTokenTestResponseDto(
    val success: Boolean,
    val message: String? = null,
)
