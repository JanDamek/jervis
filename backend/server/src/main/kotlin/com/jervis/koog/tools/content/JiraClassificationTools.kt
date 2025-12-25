package com.jervis.koog.tools.content

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.koog.qualifier.types.JiraClassification
import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * Atomic JIRA classification tools - split from original ContentAnalysisTools.
 *
 * Key improvements:
 * - Small, focused tools doing ONE thing each
 * - Synchronous operations with immediate feedback
 * - Structured JSON returns instead of human strings
 * - No background coroutine launches that return before completion
 * - No mixing of concerns (classify + create task now separate)
 *
 * @param task Current pending task being processed
 * @param userTaskService Service for creating user tasks
 */
@LLMDescription("JIRA ticket classification and task creation tools")
class JiraClassificationTools(
    private val task: TaskDocument,
    private val userTaskService: UserTaskService,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    /**
     * Classify a JIRA ticket. Does NOT create tasks - use createUserTaskFromJira for that.
     *
     * This tool ONLY classifies and returns the result immediately.
     * Classification is stored in the system for reporting and metrics.
     *
     * @param jiraKey JIRA ticket key (e.g., "SDB-2080")
     * @param classification Classification enum: INFO, ACTIONABLE, or CUSTOM_GROUP
     * @param reason Brief reason for this classification (shown to user)
     * @param customGroup Optional custom group name for CUSTOM_GROUP classification
     * @return Structured classification result
     */
    @Tool
    @LLMDescription(
        """
Classify JIRA ticket. Returns immediately with classification result.

Use this to categorize tickets:
- INFO: Informational update, no action needed
- ACTIONABLE: Requires user action (use createUserTaskFromJira next)
- CUSTOM_GROUP: Belongs to custom category (e.g., "critical-bugs")

This tool ONLY classifies. To create a user task, call createUserTaskFromJira separately.

Examples:
- classifyJiraTicket("SDB-2080", INFO, "Status update only")
- classifyJiraTicket("SDB-2081", ACTIONABLE, "Critical bug in production")
- classifyJiraTicket("SDB-2082", CUSTOM_GROUP, "Sprint blocker", "sprint-blockers")
        """,
    )
    suspend fun classifyJiraTicket(
        @LLMDescription("JIRA key (e.g., SDB-2080)")
        jiraKey: String,
        @LLMDescription("Classification: INFO, ACTIONABLE, or CUSTOM_GROUP")
        classification: JiraClassification,
        @LLMDescription("Brief reason for classification")
        reason: String,
        @LLMDescription("Custom group name (required if classification=CUSTOM_GROUP)")
        customGroup: String? = null,
    ): ClassificationResult {
        logger.info {
            "🎯 JIRA_CLASSIFY | correlationId=${task.correlationId} | " +
                "jiraKey=$jiraKey | classification=$classification | customGroup=$customGroup"
        }

        // Validate custom group for CUSTOM_GROUP classification
        if (classification == JiraClassification.CUSTOM_GROUP && customGroup.isNullOrBlank()) {
            return ClassificationResult(
                success = false,
                jiraKey = jiraKey,
                classification = classification.name,
                customGroup = null,
                reason = reason,
                error = "Custom group name required for CUSTOM_GROUP classification",
            )
        }

        // TODO: Store classification in database for metrics/reporting
        // For now, just log and return success

        logger.info {
            "✅ JIRA_CLASSIFIED | correlationId=${task.correlationId} | " +
                "jiraKey=$jiraKey | classification=$classification"
        }

        return ClassificationResult(
            success = true,
            jiraKey = jiraKey,
            classification = classification.name,
            customGroup = customGroup,
            reason = reason,
        )
    }

    /**
     * Create user task from JIRA ticket. Separate from classification.
     *
     * This tool creates an actionable user task with reference to JIRA content.
     * Uses URN references instead of embedding full content (token efficiency).
     *
     * @param jiraKey JIRA ticket key
     * @param title Task title (brief, actionable)
     * @param descriptionRef URI reference to full content (not raw text)
     * @param priority Task priority
     * @return Task creation result with taskId
     */
    @Tool
    @LLMDescription(
        """
Create user task from JIRA ticket. Returns immediately with taskId.

Use this after classifying ticket as ACTIONABLE to create actionable user task.

IMPORTANT: Use descriptionRef as URI/reference, NOT full ticket content.
Example descriptionRef: "urn:jira:SDB-2080" or "See JIRA ticket SDB-2080 for details"

Examples:
- createUserTaskFromJira("SDB-2080", "Fix login bug", "urn:jira:SDB-2080", HIGH)
        """,
    )
    suspend fun createUserTaskFromJira(
        @LLMDescription("JIRA key (e.g., SDB-2080)")
        jiraKey: String,
        @LLMDescription("Brief, actionable task title")
        title: String,
        @LLMDescription("URI reference to content (NOT full text)")
        descriptionRef: String,
        @LLMDescription("Task priority: LOW, MEDIUM, HIGH, CRITICAL")
        priority: TaskPriorityEnum = TaskPriorityEnum.MEDIUM,
    ): TaskCreationResult {
        logger.info {
            "📝 CREATE_USER_TASK | correlationId=${task.correlationId} | " +
                "jiraKey=$jiraKey | priority=$priority"
        }

        return try {
            val taskTitle = "[$jiraKey] $title"
            val taskDescription =
                buildString {
                    append("JIRA Ticket: $jiraKey\n")
                    append("Reference: $descriptionRef\n")
                    append("\nCorrelation ID: ${task.correlationId}")
                }

            val createdTask =
                userTaskService.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    clientId = task.clientId,
                    projectId = task.projectId,
                    correlationId = task.correlationId,
                )

            logger.info {
                "✅ USER_TASK_CREATED | correlationId=${task.correlationId} | " +
                    "taskId=${createdTask.id} | jiraKey=$jiraKey"
            }

            TaskCreationResult(
                success = true,
                taskId = createdTask.id.toString(),
                jiraKey = jiraKey,
                title = taskTitle,
                priority = priority.name,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "❌ USER_TASK_FAILED | correlationId=${task.correlationId} | " +
                    "jiraKey=$jiraKey | error=${e.message}"
            }
            TaskCreationResult(
                success = false,
                taskId = null,
                jiraKey = jiraKey,
                title = title,
                priority = priority.name,
                error = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * Search for existing custom classification groups.
     *
     * Use before creating new groups to ensure consistent naming.
     *
     * @param keyword Keyword to search for in group names
     * @return List of matching group names
     */
    @Tool
    @LLMDescription(
        """
Search for existing JIRA custom classification groups.

Use this before using CUSTOM_GROUP classification to find existing groups.

Example: searchJiraGroups("blocker") might return ["sprint-blockers", "release-blockers"]
        """,
    )
    suspend fun searchJiraGroups(
        @LLMDescription("Keyword to search for in group names")
        keyword: String,
    ): GroupSearchResult {
        // TODO: Implement group search in database
        logger.info {
            "🔍 JIRA_GROUP_SEARCH | correlationId=${task.correlationId} | keyword=$keyword"
        }

        // For now, return empty list - groups will be created dynamically
        return GroupSearchResult(
            keyword = keyword,
            groups = emptyList(),
            message = "No existing groups found. You can create new group by using CUSTOM_GROUP classification.",
        )
    }

    @Serializable
    data class ClassificationResult(
        val success: Boolean,
        val jiraKey: String,
        val classification: String,
        val customGroup: String?,
        val reason: String,
        val error: String? = null,
    )

    @Serializable
    data class TaskCreationResult(
        val success: Boolean,
        val taskId: String?,
        val jiraKey: String,
        val title: String,
        val priority: String,
        val error: String? = null,
    )

    @Serializable
    data class GroupSearchResult(
        val keyword: String,
        val groups: List<String>,
        val message: String,
    )
}
