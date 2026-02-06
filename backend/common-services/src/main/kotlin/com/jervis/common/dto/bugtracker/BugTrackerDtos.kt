package com.jervis.common.dto.bugtracker

import com.jervis.common.dto.AuthType
import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerUserRequest(
    val baseUrl: String,
    val authType: AuthType, // BASIC, BEARER, OAUTH2
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
)

@Serializable
data class BugTrackerUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class BugTrackerSearchRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val projectKey: String? = null,
    val query: String? = null,
    val maxResults: Int = 100,
)

@Serializable
data class BugTrackerSearchResponse(
    val issues: List<BugTrackerIssueDto>,
    val total: Int,
)

@Serializable
data class BugTrackerIssueRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val issueKey: String,
)

@Serializable
data class BugTrackerIssueResponse(
    val issue: BugTrackerIssueDto,
)

@Serializable
data class BugTrackerIssueDto(
    val id: String,
    val key: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val priority: String? = null,
    val assignee: String? = null,
    val reporter: String? = null,
    val created: String,
    val updated: String,
    val url: String,
    val projectKey: String? = null,
    val attachments: List<BugTrackerAttachmentDto> = emptyList(),
)

@Serializable
data class BugTrackerAttachmentDto(
    val id: String,
    val filename: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val contentUrl: String? = null,
)

@Serializable
data class BugTrackerProjectsRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
)

@Serializable
data class BugTrackerProjectsResponse(
    val projects: List<BugTrackerProjectDto>,
)

@Serializable
data class BugTrackerProjectDto(
    val id: String,
    val key: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
)

@Serializable
data class BugTrackerAttachmentRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val attachmentId: String,
    val attachmentUrl: String,
)
