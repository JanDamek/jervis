package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ResearchAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.model.ContextPack
import mu.KotlinLogging

/**
 * Internal orchestrator tools wrapping internal agents.
 *
 * Internal agents use Koog's retry/validation patterns - NO silent fallbacks.
 * If LLM produces invalid output → Koog automatically retries with error message.
 * Koog framework handles serialization automatically.
 */
class InternalAgentTools(
    private val task: TaskDocument,
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Get mandatory context for task execution. Always call this first. " +
            "Returns: projectPath, build/test commands, known facts, and missingInfo.",
    )
    suspend fun getContext(
        @LLMDescription("Original user request / query that the orchestrator is processing")
        userQuery: String,
    ): ContextPack {
        logger.info { "TOOL_getContext | correlationId=${task.correlationId}" }
        return contextAgent.run(task = task, userQuery = userQuery)
    }

    // Note: createPlan, gatherEvidence, reviewCompleteness removed
    // These are NOT simple data gathering tools - they require complex LLM reasoning
    // Orchestrator should use its own LLM to do planning/research/review
    // by calling simple tools (searchKnowledge, queryGraph, etc) directly
}
