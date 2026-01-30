package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multi-goal request decomposition result.
 * Represents complex user queries broken down into multiple executable goals.
 */
@Serializable
@SerialName("MultiGoalRequest")
@LLMDescription("Complex user request decomposed into multiple executable goals with dependency tracking. Use this when a single query requires multiple distinct actions or workflows.")
data class MultiGoalRequest(
    @property:LLMDescription("Original user query text before decomposition")
    val originalQuery: String,

    @property:LLMDescription("List of individual goals to execute. Each goal is independent or depends on other goals via dependencyGraph.")
    val goals: List<GoalSpec>,

    @property:LLMDescription("Goal dependencies map: goalId -> list of goalIds it depends on. Empty list means no dependencies.")
    val dependencyGraph: Map<String, List<String>> = emptyMap()
) {
    /**
     * Get goals that can be executed now (dependencies satisfied)
     */
    fun getExecutableGoals(completedGoalIds: Set<String>): List<GoalSpec> =
        goals.filter { goal ->
            goal.id !in completedGoalIds &&
            (dependencyGraph[goal.id]?.all { it in completedGoalIds } != false)
        }

    /**
     * Check if there are more goals to execute
     */
    fun hasMoreGoals(completedGoalIds: Set<String>): Boolean =
        goals.any { it.id !in completedGoalIds }
}

/**
 * Single goal specification within a multi-goal request
 */
@Serializable
@SerialName("GoalSpec")
@LLMDescription("Single executable goal specification with type, desired outcome, and validation checklist")
data class GoalSpec(
    @property:LLMDescription("Unique goal identifier (e.g., 'g1', 'g2')")
    val id: String,

    @property:LLMDescription("Goal type: ADVICE (research/info), CODE_ANALYSIS (read-only code exploration), CODE_CHANGE (actual modification), MESSAGE_DRAFT, EPIC, or BACKLOG_PROGRAM")
    val type: RequestType,

    @property:LLMDescription("Desired outcome or deliverable - what should be achieved (e.g., 'Find all NTB purchases from Alza')")
    val outcome: String,

    @property:LLMDescription("Detailed description of the goal and approach")
    val description: String = "",

    @property:LLMDescription("Entities referenced in this goal (JIRA tickets, Confluence pages, files, etc.)")
    val entities: List<EntityRef> = emptyList(),

    @property:LLMDescription("Validation checklist - steps to verify goal completion and minimize risks")
    val checklist: List<String> = emptyList(),

    @property:LLMDescription("Priority for independent goals (0=highest). Goals with dependencies ignore this.")
    val priority: Int = 0,

    @property:LLMDescription("Estimated complexity: LOW, MEDIUM, or HIGH")
    val estimatedComplexity: String = "MEDIUM",

    @property:LLMDescription("Additional metadata as key-value pairs")
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Result of executing a single goal
 */
@Serializable
@SerialName("GoalResult")
@LLMDescription("Execution result for a single goal including success status, evidence gathered, and artifacts created")
data class GoalResult(
    @property:LLMDescription("Goal identifier matching GoalSpec.id")
    val goalId: String,

    @property:LLMDescription("Type of goal that was executed")
    val goalType: RequestType,

    @property:LLMDescription("Desired outcome from goal specification")
    val outcome: String,

    @property:LLMDescription("Whether goal completed successfully")
    val success: Boolean,

    @property:LLMDescription("Evidence collected during execution (research results, analysis findings, etc.)")
    val evidence: EvidencePack?,

    @property:LLMDescription("Plan steps that were executed")
    val executedSteps: List<PlanStep>,

    @property:LLMDescription("Artifacts created (files modified, JIRA tickets created, emails sent, etc.)")
    val artifacts: List<Artifact> = emptyList(),

    @property:LLMDescription("Execution duration in milliseconds")
    val duration: Long,

    @property:LLMDescription("Error messages if success=false")
    val errors: List<String> = emptyList()
)

/**
 * Artifact created during goal execution (file, message, task, etc.)
 */
@Serializable
@SerialName("Artifact")
@LLMDescription("Artifact created during goal execution (file modification, JIRA ticket, email, Confluence page, etc.)")
data class Artifact(
    @property:LLMDescription("Artifact type: file, jira_ticket, confluence_page, email, user_task, epic, git_commit")
    val type: String,

    @property:LLMDescription("Artifact identifier (file path, JIRA key, page ID, email ID, etc.)")
    val id: String,

    @property:LLMDescription("Human-readable description of what was created or modified")
    val description: String,

    @property:LLMDescription("Optional content or preview of the artifact")
    val content: String? = null
)

/**
 * Goal selection routing decision
 */
sealed class GoalSelection {
    data class Execute(val goal: GoalSpec, val remaining: MultiGoalRequest) : GoalSelection()
    data class AllDone(val request: MultiGoalRequest, val results: List<GoalResult>) : GoalSelection()
    data class WaitingForDependencies(val request: MultiGoalRequest) : GoalSelection()
}
