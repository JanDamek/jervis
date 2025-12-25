package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.jira.JiraIssue
import com.jervis.service.jira.JiraProject
import com.jervis.service.jira.JiraService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * READ-ONLY Jira tools for context lookup.
 * Used by KoogQualifierAgent to enrich context when indexing mentions of Jira issues.
 */
@LLMDescription("Read-only Jira operations for context lookup and enrichment")
class JiraReadTools(
    private val task: TaskDocument,
    private val jiraService: JiraService,
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
    ): JiraSearchResult =
        try {
            logger.info { "JIRA_SEARCH: query='$query', project=$project, maxResults=$maxResults" }
            val issues = jiraService.searchIssues(task.clientId, query, project, maxResults)
            JiraSearchResult(
                success = true,
                issues = issues,
                totalFound = issues.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search Jira issues" }
            JiraSearchResult(
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
    ): JiraIssueResult =
        try {
            logger.info { "JIRA_GET_ISSUE: issueKey=$issueKey" }
            val issue = jiraService.getIssue(task.clientId, issueKey)
            JiraIssueResult(
                success = true,
                issue = issue,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Jira issue: $issueKey" }
            JiraIssueResult(
                success = false,
                issue = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("List all Jira projects for client. Use to discover available projects.")
    suspend fun listProjects(): JiraProjectsResult =
        try {
            logger.info { "JIRA_LIST_PROJECTS" }
            val projects = jiraService.listProjects(task.clientId)
            JiraProjectsResult(
                success = true,
                projects = projects,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Jira projects" }
            JiraProjectsResult(
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
    ): JiraCommentsResult =
        try {
            logger.info { "JIRA_GET_COMMENTS: issueKey=$issueKey" }
            val comments = jiraService.getComments(task.clientId, issueKey)
            JiraCommentsResult(
                success = true,
                comments = comments,
                totalComments = comments.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Jira comments: $issueKey" }
            JiraCommentsResult(
                success = false,
                comments = emptyList(),
                totalComments = 0,
                error = e.message ?: "Unknown error",
            )
        }
}

@Serializable
data class JiraSearchResult(
    val success: Boolean,
    val issues: List<JiraIssue>,
    val totalFound: Int,
    val error: String? = null,
)

@Serializable
data class JiraIssueResult(
    val success: Boolean,
    val issue: JiraIssue?,
    val error: String? = null,
)

@Serializable
data class JiraProjectsResult(
    val success: Boolean,
    val projects: List<JiraProject>,
    val error: String? = null,
)

@Serializable
data class JiraCommentsResult(
    val success: Boolean,
    val comments: List<com.jervis.service.jira.JiraComment>,
    val totalComments: Int,
    val error: String? = null,
)
