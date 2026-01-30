package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Common Work Item abstraction for Orchestrator.
 */
@Serializable
@SerialName("WorkItem")
@LLMDescription("Work item from issue tracker (JIRA, GitHub Issues, etc.) with dependencies and metadata. Represents Epic, Story, Task, Bug, or Change.")
data class WorkItem(
    @property:LLMDescription("Work item identifier (e.g., 'PROJ-123', 'GH-456')")
    val id: String,

    @property:LLMDescription("Item type: Epic, Story, Task, Bug, Change")
    val type: String,

    @property:LLMDescription("Current status in workflow (e.g., 'Draft', 'Ready', 'In Progress', 'Done')")
    val status: String,

    @property:LLMDescription("Short summary/title of work item")
    val summary: String,

    @property:LLMDescription("Detailed description of work to be done")
    val description: String? = null,

    @property:LLMDescription("Assigned person/team")
    val assignee: String? = null,

    @property:LLMDescription("IDs of blocking items - must be completed before this item can start")
    val dependencies: List<String> = emptyList(),

    @property:LLMDescription("IDs of child items - sub-tasks or related work items")
    val children: List<String> = emptyList(),

    @property:LLMDescription("Additional metadata (labels, priority, custom fields)")
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Normalized Workflow Definition.
 */
@Serializable
@SerialName("WorkflowDefinition")
@LLMDescription("Workflow definition for a specific work item type. Defines available states, allowed transitions, and state groupings.")
data class WorkflowDefinition(
    @property:LLMDescription("Item type this workflow applies to (e.g., 'Story', 'Bug')")
    val itemType: String,

    @property:LLMDescription("List of all possible states in workflow")
    val states: List<String>,

    @property:LLMDescription("Allowed state transitions with requirements")
    val transitions: List<WorkflowTransition>,

    @property:LLMDescription("Mapping of states to orchestrator state groups (draft, ready, doing, review, blocked, done)")
    val stateGroups: WorkflowMapping
)

@Serializable
@SerialName("WorkflowTransition")
@LLMDescription("Single workflow transition from one state to another with requirements.")
data class WorkflowTransition(
    @property:LLMDescription("Source state")
    val from: String,

    @property:LLMDescription("Target state")
    val to: String,

    @property:LLMDescription("Fields that must be filled before transition (e.g., 'assignee', 'priority')")
    val requiredFields: List<String> = emptyList(),

    @property:LLMDescription("Whether this transition requires approval from user/manager")
    val requiresApproval: Boolean = false
)

/**
 * Heuristic mapping of tracker states to orchestrator state groups.
 */
@Serializable
@SerialName("WorkflowMapping")
@LLMDescription("Mapping of tracker-specific states to orchestrator state groups. Used to determine if work item is ready for execution.")
data class WorkflowMapping(
    @property:LLMDescription("Draft states - item not yet ready (e.g., 'Draft', 'Backlog')")
    val draftStates: Set<String> = emptySet(),

    @property:LLMDescription("Execution-ready states - item approved and ready to work on (e.g., 'Ready', 'To Do')")
    val executionReadyStates: Set<String> = emptySet(),

    @property:LLMDescription("Active work states - item being worked on (e.g., 'In Progress', 'Development')")
    val doingStates: Set<String> = emptySet(),

    @property:LLMDescription("Review states - work done, awaiting review (e.g., 'Code Review', 'Testing')")
    val reviewStates: Set<String> = emptySet(),

    @property:LLMDescription("Blocked states - item cannot proceed (e.g., 'Blocked', 'Waiting')")
    val blockedStates: Set<String> = emptySet(),

    @property:LLMDescription("Terminal states - item completed or cancelled (e.g., 'Done', 'Closed', 'Cancelled')")
    val terminalStates: Set<String> = emptySet(),

    @property:LLMDescription("Confidence score 0.0-1.0 for this mapping heuristic")
    val confidence: Double = 1.0
)
