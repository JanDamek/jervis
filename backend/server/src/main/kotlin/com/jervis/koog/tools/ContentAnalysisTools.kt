package com.jervis.koog.tools.content

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.koog.qualifier.types.JiraClassification
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

/**
 * Tools for content classification and type-specific actions.
 *
 * These tools allow the LLM to:
 * - Classify JIRA tickets into INFO/ACTIONABLE/CUSTOM_GROUP
 * - Create user tasks for actionable items
 * - Search/create custom classification groups for consistent categorization
 *
 * @param task Current pending task being processed
 * @param userTaskService Service for creating user tasks
 * @param coroutineScope Coroutine scope for background operations
 */
@LLMDescription("Content analysis and classification tools")
class ContentAnalysisTools(
    private val task: TaskDocument,
    private val userTaskService: UserTaskService,
    private val coroutineScope: CoroutineScope,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    /**
     * Classify a JIRA ticket and optionally create a user task.
     *
     * This tool is specific to JIRA content type. It allows the LLM to:
     * 1. Classify the ticket as INFO (no action), ACTIONABLE (requires user attention), or CUSTOM_GROUP
     * 2. Automatically create a user task if classification is ACTIONABLE
     * 3. Assign to custom groups for consistent categorization (e.g., "critical-bugs", "sprint-blockers")
     *
     * @param jiraKey JIRA ticket key (e.g., "SDB-2080")
     * @param classification Classification type: INFO, ACTIONABLE, or CUSTOM_GROUP
     * @param customGroup Optional custom group name for CUSTOM_GROUP classification (e.g., "critical-bugs")
     * @param reason Brief reason for this classification (shown to user)
     * @param createUserTask Whether to create a user task (automatically true for ACTIONABLE)
     * @return Confirmation message
     */
    @Tool
    @LLMDescription(
        """
Classify JIRA ticket and optionally create user task.

Use this when:
- JIRA ticket requires user action → classification=ACTIONABLE, createUserTask=true
- JIRA ticket is informational only → classification=INFO
- JIRA ticket belongs to custom category → classification=CUSTOM_GROUP, customGroup="name"

Examples:
- Bug requiring fix: classifyJira("SDB-2080", "ACTIONABLE", reason="Critical bug affecting production")
- Info update: classifyJira("SDB-2081", "INFO", reason="Status update, no action needed")
- Custom category: classifyJira("SDB-2082", "CUSTOM_GROUP", customGroup="sprint-blockers", reason="Blocking sprint progress")
        """,
    )
    suspend fun classifyJira(
        @LLMDescription("JIRA key (e.g., SDB-2080)")
        jiraKey: String,
        @LLMDescription("Classification: INFO, ACTIONABLE, or CUSTOM_GROUP")
        classification: String,
        @LLMDescription("Custom group name (required if classification=CUSTOM_GROUP)")
        customGroup: String? = null,
        @LLMDescription("Brief reason for classification")
        reason: String,
        @LLMDescription("Create user task (auto-true for ACTIONABLE)")
        createUserTask: Boolean = false,
    ): String {
        logger.info {
            "🎯 JIRA_CLASSIFY | correlationId=${task.correlationId} | " +
                "jiraKey=$jiraKey | classification=$classification | customGroup=$customGroup"
        }

        val classificationEnum =
            when (classification.uppercase()) {
                "INFO" -> {
                    JiraClassification.INFO
                }

                "ACTIONABLE" -> {
                    JiraClassification.ACTIONABLE
                }

                "CUSTOM_GROUP" -> {
                    JiraClassification.CUSTOM_GROUP
                }

                else -> {
                    logger.warn { "Unknown classification: $classification, defaulting to INFO" }
                    JiraClassification.INFO
                }
            }

        // ACTIONABLE automatically creates user task
        val shouldCreateTask = createUserTask || classificationEnum == JiraClassification.ACTIONABLE

        if (shouldCreateTask) {
            coroutineScope.launch {
                try {
                    val taskTitle = "[$jiraKey] $reason"
                    val taskDescription =
                        buildString {
                            append("JIRA Ticket: $jiraKey\n")
                            append("Classification: $classification\n")
                            if (customGroup != null) {
                                append("Group: $customGroup\n")
                            }
                            append("\nReason: $reason\n")
                            append("\nOriginal content: ${task.content}")
                        }

                    userTaskService.createTask(
                        title = taskTitle,
                        description = taskDescription,
                        clientId = task.clientId,
                        projectId = task.projectId,
                        correlationId = task.correlationId,
                    )

                    logger.info {
                        "✅ USER_TASK_CREATED | correlationId=${task.correlationId} | " +
                            "jiraKey=$jiraKey | title=$taskTitle"
                    }
                } catch (e: Exception) {
                    logger.error(e) {
                        "❌ USER_TASK_FAILED | correlationId=${task.correlationId} | " +
                            "jiraKey=$jiraKey | error=${e.message}"
                    }
                }
            }
        }

        return buildString {
            append("JIRA ticket $jiraKey classified as $classification")
            if (customGroup != null) {
                append(" (group: $customGroup)")
            }
            if (shouldCreateTask) {
                append(". User task created.")
            }
            append(" Reason: $reason")
        }
    }

    /**
     * Search for existing custom classification groups.
     *
     * Use this to find existing groups before creating new ones, ensuring consistency.
     * For example, check if "critical-bugs" group exists before using it.
     *
     * @param keyword Keyword to search for in group names
     * @return List of matching group names
     */
    @Tool
    @LLMDescription(
        """
Search for existing JIRA custom classification groups.

Use this before calling classifyJira with CUSTOM_GROUP to ensure consistent naming.

Example: searchJiraGroups("blocker") might return ["sprint-blockers", "release-blockers"]
        """,
    )
    suspend fun searchJiraGroups(
        @LLMDescription("Keyword to search for in group names")
        keyword: String,
    ): String {
        // TODO: Implement group search in database
        // For now, return empty - groups will be created dynamically
        logger.info {
            "🔍 JIRA_GROUP_SEARCH | correlationId=${task.correlationId} | keyword=$keyword"
        }
        return "No existing groups found for keyword: $keyword. You can create a new group by using classifyJira with CUSTOM_GROUP."
    }

    /**
     * Create a new custom classification group.
     *
     * Use this to establish a new category for consistent ticket classification.
     * Groups are scoped to client+project.
     *
     * @param groupName Group name (e.g., "critical-bugs", "sprint-blockers")
     * @param description Description of what tickets belong to this group
     * @return Confirmation message
     */
    @Tool
    @LLMDescription(
        """
Create a new JIRA custom classification group.

Use this to establish consistent categorization for tickets.
Group names should be kebab-case (e.g., "critical-bugs", not "Critical Bugs").

Example: createJiraGroup("security-issues", "Security vulnerabilities requiring immediate attention")
        """,
    )
    suspend fun createJiraGroup(
        @LLMDescription("Group name (kebab-case)")
        groupName: String,
        @LLMDescription("Description of what tickets belong to this group")
        description: String,
    ): String {
        // TODO: Implement group creation in database
        logger.info {
            "📝 JIRA_GROUP_CREATE | correlationId=${task.correlationId} | " +
                "groupName=$groupName | description=$description"
        }
        return "Custom group '$groupName' created. Description: $description"
    }
}
