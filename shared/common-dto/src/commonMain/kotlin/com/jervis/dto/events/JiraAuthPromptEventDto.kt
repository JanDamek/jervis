package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class JiraAuthPromptEventDto(
    val eventType: String = "JIRA_AUTH_PROMPT",
    val clientId: String,
    val correlationId: String,
    val authUrl: String,
    val redirectUri: String,
)
