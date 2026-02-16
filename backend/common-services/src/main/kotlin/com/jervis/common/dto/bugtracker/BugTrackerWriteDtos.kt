package com.jervis.common.dto.bugtracker

import com.jervis.common.dto.AuthType
import kotlinx.serialization.Serializable

// ==================== WRITE RPC DTOs ====================

@Serializable
data class BugTrackerCreateIssueRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val projectKey: String,
    val summary: String,
    val description: String? = null,
    val issueType: String = "Task",
    val priority: String? = null,
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
    val epicKey: String? = null,
)

@Serializable
data class BugTrackerUpdateIssueRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val issueKey: String,
    val summary: String? = null,
    val description: String? = null,
    val assignee: String? = null,
    val priority: String? = null,
    val labels: List<String>? = null,
)

@Serializable
data class BugTrackerAddCommentRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val issueKey: String,
    val body: String,
)

@Serializable
data class BugTrackerTransitionRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val issueKey: String,
    val transitionName: String,
)

@Serializable
data class BugTrackerCommentResponse(
    val id: String,
    val author: String? = null,
    val body: String,
    val created: String,
)
