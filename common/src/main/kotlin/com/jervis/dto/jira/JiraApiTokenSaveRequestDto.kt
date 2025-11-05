package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraApiTokenSaveRequestDto(
    val clientId: String,
    val tenant: String,
    val email: String,
    val apiToken: String,
)
