package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.integration.bugtracker.BugTrackerComment
import com.jervis.integration.bugtracker.BugTrackerIssue
import com.jervis.integration.bugtracker.BugTrackerProject
import com.jervis.integration.bugtracker.BugTrackerService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * READ-ONLY bug tracker tools for context lookup.
 * Used by KoogQualifierAgent to enrich context when indexing mentions of bug tracker issues.
 * Supports Jira, GitHub Issues, GitLab Issues, etc.
 */
@LLMDescription("Read-only bug tracker operations for context lookup and enrichment (Jira, GitHub, GitLab)")
class BugTrackerReadTools(
    private val task: TaskDocument,
    private val jiraService: BugTrackerService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("Search Jira issues by JQL query. Use to find relevant issues by keywords, project, status, assignee, etc.")
    suspend fun searchIssues(
        @LLMDescription("JQL query (e.g., 'project = PROJ AND status = Open')")
        query: String,
        @LLMDescription("Project key to filter by (optional)")
        project: String? = null,
        @LLMDescription("Max results to return")
        maxResults: Int = 20,
    ): BugTrackerSearchResult =
        try {
            logger.info { "JIRA_SEARCH: query='$query', project=$project, maxResults=$maxResults" }
            val issues = jiraService.searchIssues(task.clientId, query, project, maxResults)
            BugTrackerSearchResult(
                success = true,
                issues = issues,
                totalFound = issues.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search Jira issues" }
            BugTrackerSearchResult(
                success = false,
                issues = emptyList(),
                totalFound = 0,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get specific Jira issue by key (e.g., PROJ-123). Use to retrieve full details about mentioned issue.")
    suspend fun getIssue(
        @LLMDescription("Issue key (e.g., PROJ-123)")
        issueKey: String,
    ): BugTrackerIssueResult =
        try {
            logger.info { "JIRA_GET_ISSUE: issueKey=$issueKey" }
            val issue = jiraService.getIssue(task.clientId, issueKey)

            // Full description is returned without truncation to preserve context.
            // EvidencePack.MAX_CONTENT_LENGTH is used only as a hint for UI summary.

            BugTrackerIssueResult(
                success = true,
                issue = issue,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Jira issue: $issueKey" }
            BugTrackerIssueResult(
                success = false,
                issue = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("List all Jira projects for client. Use to discover available projects.")
    suspend fun listProjects(): BugTrackerProjectsResult =
        try {
            logger.info { "JIRA_LIST_PROJECTS" }
            val projects = jiraService.listProjects(task.clientId)
            BugTrackerProjectsResult(
                success = true,
                projects = projects,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Jira projects" }
            BugTrackerProjectsResult(
                success = false,
                projects = emptyList(),
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get comments for specific Jira issue. Use to see discussion history.")
    suspend fun getComments(
        @LLMDescription("Issue key (e.g., PROJ-123)")
        issueKey: String,
    ): BugTrackerCommentsResult =
        try {
            logger.info { "JIRA_GET_COMMENTS: issueKey=$issueKey" }
            val comments = jiraService.getComments(task.clientId, issueKey)
            BugTrackerCommentsResult(
                success = true,
                comments = comments,
                totalComments = comments.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Jira comments: $issueKey" }
            BugTrackerCommentsResult(
                success = false,
                comments = emptyList(),
                totalComments = 0,
                error = e.message ?: "Unknown error",
            )
        }
}

@Serializable
data class BugTrackerSearchResult(
    val success: Boolean,
    val issues: List<BugTrackerIssue>,
    val totalFound: Int,
    val error: String? = null,
)

@Serializable
data class BugTrackerIssueResult(
    val success: Boolean,
    val issue: BugTrackerIssue?,
    val error: String? = null,
)

@Serializable
data class BugTrackerProjectsResult(
    val success: Boolean,
    val projects: List<BugTrackerProject>,
    val error: String? = null,
)

@Serializable
data class BugTrackerCommentsResult(
    val success: Boolean,
    val comments: List<BugTrackerComment>,
    val totalComments: Int,
    val error: String? = null,
)
