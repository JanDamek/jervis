package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraBeginAuthRequestDto(
    val clientId: String,
    val redirectUri: String,
    val tenant: String, // Atlassian Cloud base host, e.g. your-domain.atlassian.net
)
