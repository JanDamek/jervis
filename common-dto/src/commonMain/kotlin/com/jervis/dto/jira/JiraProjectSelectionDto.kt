package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraProjectSelectionDto(
    val clientId: String,
    val projectKey: String,
)
