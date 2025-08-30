package com.jervis.service.agent.planner

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.PlanStatus
import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStep
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpToolRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Planner creates a plan for a given task context.
 * Prefers LLM-driven plan, falls back to deterministic default when needed.
 */
@Service
class Planner(
    private val toolRegistry: McpToolRegistry,
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    suspend fun createPlan(context: TaskContextDocument): PlanDocument {
        val systemPrompt = buildSystemPrompt(toolRegistry.getAllToolDescriptionsJson())
        val userPrompt = buildUserPrompt(context)
        logger.debug { "PLANNER_PROMPT_START\nSYSTEM:\n$systemPrompt\nUSER:\n$userPrompt\nPLANNER_PROMPT_END" }

        val llmResponse =
            try {
                llmGateway
                    .callLlm(
                        type = ModelType.PLANNER,
                        userPrompt = userPrompt,
                        systemPrompt = systemPrompt,
                        quick = context.quick,
                    )
            } catch (e: Exception) {
                logger.error(e) { "PLANNER_LLM_ERROR: falling back to deterministic plan" }
                null
            }
        val llmAnswer: String = llmResponse?.answer?.trim().orEmpty()
        if (llmAnswer.isNotBlank()) {
            logger.debug { "PLANNER_LLM_RESPONSE_START\n$llmAnswer\nPLANNER_LLM_RESPONSE_END" }
        }

        val steps = parseAnswerToSteps(llmAnswer)

        val plan =
            PlanDocument(
                contextId = context.contextId,
                status = PlanStatus.CREATED,
                steps = steps,
            )
        logger.info { "PLANNER_LLM_PLAN_BUILT: model=${llmResponse?.model ?: "n/a"} steps=${plan.steps.size}" }
        plan.steps.forEachIndexed { index, step ->
            logger.debug { "  - Step ${index + 1}: Tool='${step.name}', params=${step.parameters}" }
        }
        return plan
    }

    private fun buildSystemPrompt(toolsJsonLines: String): String =
        """You are an autonomous step-by-step planning agent.
Your job is to generate the shortest actionable plan to fulfill the user's task using ONLY available tools.

Output format:
Return a JSON array (no wrapper), where each item is:
{
  "name": "<tool.name>",
  "parameters": "<short instruction describing the action to take using this tool, fully customized to the task, client, and project>"
}

Strict constraints:
- Use ONLY tools listed in the Tool List (match "name" exactly and case-sensitive).
- Each step MUST be directly actionable.
- Parameters MUST be specific to the task context (including project, client, and user intent).
- DO NOT explain anything. DO NOT add non-JSON content. DO NOT wrap in an object.
- Output ONLY pure JSON.

Tool List:
One tool per line in JSON format:
$toolsJsonLines
"""

    private fun buildUserPrompt(context: TaskContextDocument): String =
        buildString {
            appendLine("Task: ${context.initialQuery}")
            appendLine("Client: ${context.clientName ?: "unknown"}")
            appendLine("Project: ${context.projectName ?: "unknown"}")
        }

    private fun parseAnswerToSteps(inputJson: String): List<PlanStep> {
        if (inputJson.isBlank()) return emptyList()

        return try {
            val raw = mapper.readTree(inputJson)
            if (!raw.isArray) throw IllegalArgumentException("LLM answer is not a JSON array")
            raw.map { node ->
                val name =
                    node
                        .get("name")
                        ?.asText()
                        ?.trim()
                        .orEmpty()
                var parameters =
                    node
                        .get("parameters")
                        ?.asText()
                        ?.trim()
                        .orEmpty()

                // Basic sanity check
                if (name.isBlank() || parameters.isBlank()) {
                    throw IllegalArgumentException("Invalid step: missing name or parameters")
                }

                // Optional post-processing: enrich parameters for rag.query, etc.
                if (name == "rag.query" && parameters.startsWith("Perform a placeholder")) {
                    parameters = "Query semantic index for project and client context."
                }

                PlanStep(name = name, parameters = parameters)
            }
        } catch (e: Exception) {
            logger.warn(e) { "PLANNER_STEP_PARSE_ERROR: LLM answer could not be parsed" }
            emptyList()
        }
    }
}
