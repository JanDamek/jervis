package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraUserSelectionDto(
    val clientId: String,
    val accountId: String,
)
