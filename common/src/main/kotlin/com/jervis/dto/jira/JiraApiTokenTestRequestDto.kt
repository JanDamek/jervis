package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraApiTokenTestRequestDto(
    val tenant: String,
    val email: String,
    val apiToken: String,
)
