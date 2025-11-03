package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraSetupStatusDto(
    val clientId: String,
    val connected: Boolean,
    val tenant: String? = null,
    val primaryProject: String? = null,
    val mainBoard: Long? = null,
    val preferredUser: String? = null,
)

@Serializable
data class JiraBeginAuthRequestDto(
    val clientId: String,
    val redirectUri: String,
    val tenant: String, // Atlassian Cloud base host, e.g. your-domain.atlassian.net
)

@Serializable
data class JiraBeginAuthResponseDto(
    val correlationId: String,
    val authUrl: String,
)

@Serializable
data class JiraCompleteAuthRequestDto(
    val clientId: String,
    val code: String,
    val verifier: String,
    val redirectUri: String,
    val correlationId: String,
    val tenant: String,
)

@Serializable
data class JiraApiTokenTestRequestDto(
    val tenant: String,
    val email: String,
    val apiToken: String,
)

@Serializable
data class JiraApiTokenTestResponseDto(
    val success: Boolean,
    val message: String? = null,
)

@Serializable
data class JiraApiTokenSaveRequestDto(
    val clientId: String,
    val tenant: String,
    val email: String,
    val apiToken: String,
)

@Serializable
data class JiraProjectSelectionDto(
    val clientId: String,
    val projectKey: String,
)

@Serializable
data class JiraBoardSelectionDto(
    val clientId: String,
    val boardId: Long,
)

@Serializable
data class JiraUserSelectionDto(
    val clientId: String,
    val accountId: String,
)

// Lightweight list DTOs for UI selection
@Serializable
data class JiraProjectRefDto(
    val key: String,
    val name: String,
)

@Serializable
data class JiraBoardRefDto(
    val id: Long,
    val name: String,
)
