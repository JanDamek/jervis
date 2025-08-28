package com.jervis.service.agent.planner

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.entity.mongo.PlanStep
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpToolRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Planner creates a plan for a given task context.
 * For now it deterministically creates a default plan while emitting detailed logs,
 * but it is designed to be LLM-driven in future iterations.
 */
@Service
class Planner(
    private val toolRegistry: McpToolRegistry,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createPlan(context: TaskContextDocument): PlanDocument {
        val availableTools = toolRegistry.getAllToolDescriptions()
        val prompt = buildPlannerPrompt(context, availableTools)
        logger.debug { "PLANNER_PROMPT_START\n$prompt\nPLANNER_PROMPT_END" }

        // Placeholder for LLM call; we keep deterministic steps for now.
        // logger.debug { "PLANNER_LLM_RESPONSE: <placeholder - deterministic default plan>" }

        val steps =
            listOf(
                PlanStep(name = AgentConstants.DefaultSteps.SCOPE_RESOLVE),
                PlanStep(name = AgentConstants.DefaultSteps.CONTEXT_ECHO),
                PlanStep(name = AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE),
                PlanStep(name = AgentConstants.DefaultSteps.RAG_QUERY),
            )
        val plan =
            PlanDocument(
                contextId = context.contextId,
                status = PlanStatus.CREATED,
                steps = steps,
            )
        logger.info {
            "PLANNER_RESULT: Plan created with ${plan.steps.size} steps for goal: '${
                context.initialQuery.take(
                    80,
                )
            }'"
        }
        plan.steps.forEachIndexed { index, step ->
            logger.debug { "  - Step ${index + 1}: Tool='${step.name}'" }
        }
        return plan
    }

    private fun buildPlannerPrompt(
        context: TaskContextDocument,
        toolDescriptions: List<String>,
    ): String {
        val tools = toolDescriptions.joinToString("\n") { "- $it" }
        return """
            You are a planning system. Given the task context below, propose a minimal sequence of tools to reach the goal.
            Only use available tools. For now, prefer the default sequence when in doubt.
            
            Context:
            - client: ${context.clientName ?: "unknown"}
            - project: ${context.projectName ?: "unknown"}
            - initialQuery: ${context.initialQuery.take(200)}
            
            Available tools:
            $tools
        """.trimIndent()
    }
}
