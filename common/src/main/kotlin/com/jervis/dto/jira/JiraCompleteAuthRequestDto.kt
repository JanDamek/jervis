package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraCompleteAuthRequestDto(
    val clientId: String,
    val code: String,
    val verifier: String,
    val redirectUri: String,
    val correlationId: String,
    val tenant: String,
)
