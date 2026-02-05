package com.jervis.domain.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument

/**
 * TaskContext - Execution context for Koog Workflow Agent.
 *
 * Simplified for Koog 0.5.3 architecture:
 * - No PlanExecutor (removed) - AgentOrchestratorService directly calls KoogWorkflowAgent
 * - Routing handled by TaskRouting object (not metadata map - antipattern!)
 * - Agent works internally in English, responds in originalLanguage
 */
data class TaskContext(
    val query: String,
    /** Language of user's instruction (for response translation) */
    val originalLanguage: String,
    /** Client context */
    val clientDocument: ClientDocument,
    /** Optional project context */
    val projectDocument: ProjectDocument? = null,
    /** Background execution mode */
    val backgroundMode: Boolean = false,
    /** Correlation ID for tracking across services */
    val correlationId: String,
) {
    val clientId: ClientId
        get() = clientId

    val projectId: ProjectId?
        get() = projectId

    override fun toString(): String =
        "TaskContext(client: ${clientDocument.name}, project: ${projectDocument?.name ?: "none"}, background: $backgroundMode) "
}
