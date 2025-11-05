package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraBeginAuthResponseDto(
    val correlationId: String,
    val authUrl: String,
)
