package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.orchestrator.model.WorkItem
import com.jervis.orchestrator.model.WorkflowDefinition
import com.jervis.integration.bugtracker.BugTrackerService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * IssueTrackerTool adapter for Jira.
 * Provides write operations and workflow information.
 */
@LLMDescription("Write operations for Jira and workflow information")
class IssueTrackerTool(
    private val task: TaskDocument,
    private val jiraService: BugTrackerService,
    private val trackerAdapter: com.jervis.orchestrator.WorkTrackerAdapter,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("Add a comment to a Jira issue. Use for reporting progress or asking for clarification.")
    suspend fun addComment(
        @LLMDescription("Issue key (e.g., PROJ-123)")
        issueKey: String,
        @LLMDescription("Comment text")
        comment: String,
    ): TrackerActionResult =
        try {
            // Použijeme adapter, který může mít implementovanou logiku pro suggest/direct
            trackerAdapter.suggestComment(task.clientId, issueKey, comment)

            // Also notify BugTrackerService directly if write operations are enabled
            runCatching {
                jiraService.addComment(task.clientId, issueKey, comment)
            }

            TrackerActionResult(success = true, message = "Comment suggested/added successfully")
        } catch (e: Exception) {
            logger.error(e) { "Failed to add comment to $issueKey" }
            TrackerActionResult(success = false, error = e.message)
        }

    @Tool
    @LLMDescription("Get workflow definition for an item type. Use to see allowed transitions and state groups.")
    suspend fun getWorkflow(
        @LLMDescription("Item type (e.g., Epic, Task, Bug)")
        itemType: String,
    ): WorkflowResult =
        try {
            val workflow =
                trackerAdapter.getWorkflow(
                    task.clientId,
                    itemType,
                    task.projectId,
                )
            WorkflowResult(success = true, workflow = workflow)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get workflow for $itemType" }
            WorkflowResult(success = false, error = e.message)
        }

    @Tool
    @LLMDescription("Suggest or perform a transition for a Jira issue.")
    suspend fun transition(
        @LLMDescription("Issue key (e.g., PROJ-123)")
        issueKey: String,
        @LLMDescription("Target state name")
        toState: String,
    ): TrackerActionResult =
        try {
            trackerAdapter.suggestTransition(task.clientId, issueKey, toState)
            TrackerActionResult(success = true, message = "Transition suggested successfully")
        } catch (e: Exception) {
            logger.error(e) { "Failed to transition $issueKey to $toState" }
            TrackerActionResult(success = false, error = e.message)
        }

    @Tool
    @LLMDescription("List child items for a parent item (e.g., Epic children).")
    suspend fun listChildren(
        @LLMDescription("Parent item ID (e.g., Epic key)")
        parentId: String,
    ): ChildrenResult =
        try {
            val children = trackerAdapter.listChildren(task.clientId, parentId)
            ChildrenResult(success = true, children = children)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list children for $parentId" }
            ChildrenResult(success = false, error = e.message)
        }

    @Tool
    @LLMDescription("Get dependencies (blocking items) for a specific item.")
    suspend fun getDependencies(
        @LLMDescription("Item ID")
        itemId: String,
    ): ChildrenResult =
        try {
            val deps = trackerAdapter.getDependencies(task.clientId, itemId)
            ChildrenResult(success = true, children = deps)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get dependencies for $itemId" }
            ChildrenResult(success = false, error = e.message)
        }

    @Serializable
    data class TrackerActionResult(
        val success: Boolean,
        val message: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class WorkflowResult(
        val success: Boolean,
        val workflow: WorkflowDefinition? = null,
        val error: String? = null,
    )

    @Serializable
    data class ChildrenResult(
        val success: Boolean,
        val children: List<WorkItem> = emptyList(),
        val error: String? = null,
    )
}
