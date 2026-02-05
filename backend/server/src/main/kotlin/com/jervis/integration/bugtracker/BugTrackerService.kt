package com.jervis.integration.bugtracker

import com.jervis.common.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Bug tracker integration service (READ + WRITE operations).
 * Generic interface supporting Jira, GitHub Issues, GitLab Issues, etc.
 * Vendor-specific implementations are in microservices (service-atlassian, service-github, etc.)
 */
interface BugTrackerService {
    /**
     * Search issues by query (e.g., JQL for Jira, GitHub search syntax for GitHub).
     */
    suspend fun searchIssues(
        clientId: ClientId,
        query: String,
        project: String? = null,
        maxResults: Int = 20,
    ): List<BugTrackerIssue>

    /**
     * Get specific issue by key (e.g., PROJ-123 for Jira, #123 for GitHub).
     */
    suspend fun getIssue(
        clientId: ClientId,
        issueKey: String,
    ): BugTrackerIssue

    /**
     * List all projects for client.
     */
    suspend fun listProjects(clientId: ClientId): List<BugTrackerProject>

    /**
     * Get comments for specific issue.
     */
    suspend fun getComments(
        clientId: ClientId,
        issueKey: String,
    ): List<BugTrackerComment>

    // ==================== WRITE OPERATIONS (Future) ====================

    /**
     * Create new issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun createIssue(
        clientId: ClientId,
        request: CreateBugTrackerIssueRequest,
    ): BugTrackerIssue

    /**
     * Update existing issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun updateIssue(
        clientId: ClientId,
        issueKey: String,
        request: UpdateBugTrackerIssueRequest,
    ): BugTrackerIssue

    /**
     * Add comment to issue.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun addComment(
        clientId: ClientId,
        issueKey: String,
        comment: String,
    ): BugTrackerComment
}

@Serializable
data class BugTrackerIssue(
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
data class BugTrackerProject(
    val key: String,
    val name: String,
    val description: String?,
)

@Serializable
data class BugTrackerComment(
    val id: String,
    val author: String,
    val body: String,
    val created: String,
)

@Serializable
data class CreateBugTrackerIssueRequest(
    val projectKey: String,
    val summary: String,
    val description: String?,
    val issueType: String,
    val priority: String? = null,
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
data class UpdateBugTrackerIssueRequest(
    val summary: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val priority: String? = null,
    val labels: List<String>? = null,
)
