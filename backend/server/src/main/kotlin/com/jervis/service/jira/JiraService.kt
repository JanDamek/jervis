package com.jervis.service.jira

import com.jervis.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Jira integration service (READ + WRITE operations).
 * Currently a TODO/mock interface - implementation pending.
 */
interface JiraService {
    // ==================== READ OPERATIONS ====================

    /**
     * Search Jira issues by JQL query.
     */
    suspend fun searchIssues(
        clientId: ClientId,
        query: String,
        project: String? = null,
        maxResults: Int = 20,
    ): List<JiraIssue>

    /**
     * Get specific Jira issue by key (e.g., PROJ-123).
     */
    suspend fun getIssue(
        clientId: ClientId,
        issueKey: String,
    ): JiraIssue

    /**
     * List all Jira projects for client.
     */
    suspend fun listProjects(clientId: ClientId): List<JiraProject>

    /**
     * Get comments for specific issue.
     */
    suspend fun getComments(
        clientId: ClientId,
        issueKey: String,
    ): List<JiraComment>

    // ==================== WRITE OPERATIONS (Future) ====================

    /**
     * Create new Jira issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun createIssue(
        clientId: ClientId,
        request: CreateJiraIssueRequest,
    ): JiraIssue

    /**
     * Update existing Jira issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun updateIssue(
        clientId: ClientId,
        issueKey: String,
        request: UpdateJiraIssueRequest,
    ): JiraIssue

    /**
     * Add comment to Jira issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun addComment(
        clientId: ClientId,
        issueKey: String,
        comment: String,
    ): JiraComment
}

// ==================== DATA MODELS ====================

@Serializable
data class JiraIssue(
    val key: String,
    val summary: String,
    val description: String?,
    val status: String,
    val assignee: String?,
    val reporter: String,
    val created: String,
    val updated: String,
    val issueType: String,
    val priority: String?,
    val labels: List<String> = emptyList(),
)

@Serializable
data class JiraProject(
    val key: String,
    val name: String,
    val description: String?,
)

@Serializable
data class JiraComment(
    val id: String,
    val author: String,
    val body: String,
    val created: String,
)

@Serializable
data class CreateJiraIssueRequest(
    val projectKey: String,
    val summary: String,
    val description: String?,
    val issueType: String,
    val priority: String? = null,
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
data class UpdateJiraIssueRequest(
    val summary: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val priority: String? = null,
    val labels: List<String>? = null,
)
