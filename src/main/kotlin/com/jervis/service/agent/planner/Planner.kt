package com.jervis.service.agent.planner

// ... existing code ...
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.PlanStatus
import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStep
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import mu.KotlinLogging
import org.springframework.stereotype.Service

// ... existing code ...

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

        val steps: Flow<PlanStep> = parseAnswerToSteps(llmAnswer)

        val plan =
            PlanDocument(
                contextId = context.id,
                status = PlanStatus.CREATED,
                steps = steps,
            )
        context.plans = flowOf(plan)
        logger.info { "PLANNER_LLM_PLAN_BUILT: model=${llmResponse?.model ?: "n/a"}" }
        return plan
    }

    private fun buildSystemPrompt(toolsJsonLines: String): String =
        """You are an autonomous step-by-step planning agent.
Your job is to generate the shortest actionable plan to fulfill the user's task using ONLY available tools.

Output format:
ALWAYS return a JSON array (no wrapper), even if there is only one step. Each item must be:
{
  "name": "<tool.name>",
  "parameters": "<short instruction describing the action to take using this tool, fully customized to the task, client, and project>"
}

Strict constraints:
- Use ONLY tools listed in the Tool List (match "name" exactly and case-sensitive).
- Each step MUST be directly actionable.
- Parameters MUST be specific to the task context (including project, client, and user intent).
- DO NOT explain anything. DO NOT add non-JSON content. DO NOT wrap in an object.
- DO NOT use code fences or backticks.
- Output ONLY pure JSON array.

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

    private fun parseAnswerToSteps(inputJson: String): Flow<PlanStep> {
        var cnt = 0
        if (inputJson.isBlank()) return emptyList<PlanStep>().asFlow()

        fun toPlanStep(node: com.fasterxml.jackson.databind.JsonNode): PlanStep {
            val name = node["name"]?.asText()?.trim().orEmpty()
            val parameters = node["parameters"]?.asText()?.trim().orEmpty()
            require(!(name.isBlank() || parameters.isBlank())) { "Invalid step: missing name or parameters" }
            return PlanStep(name = name, parameters = parameters, order = ++cnt)
        }

        fun findArrayWithSteps(obj: com.fasterxml.jackson.databind.JsonNode): List<PlanStep>? {
            val fields = obj.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val value = entry.value
                if (value.isArray && value.all { it.isObject && it.has("name") && it.has("parameters") }) {
                    return value.map { toPlanStep(it) }
                }
            }
            return null
        }

        fun stripCodeFences(s: String): String {
            val trimmed = s.trim()
            if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length >= 6) {
                val inner = trimmed.removePrefix("```").removeSuffix("```").trim()
                val firstNewline = inner.indexOf('\n')
                val languageHint = if (firstNewline >= 0) inner.substring(0, firstNewline).trim() else ""
                val withoutLang =
                    if (languageHint.matches(Regex("(?i)json|javascript|ts|typescript|kotlin|java|txt"))) {
                        if (firstNewline >= 0) inner.substring(firstNewline + 1) else ""
                    } else {
                        inner
                    }
                return withoutLang.trim()
            }
            return s
        }

        tailrec fun parseNode(
            text: String,
            depth: Int = 0,
        ): List<PlanStep> {
            if (depth > 2) return emptyList()
            val cleaned = stripCodeFences(text)
            val raw =
                try {
                    mapper.readTree(cleaned)
                } catch (e: Exception) {
                    logger.warn(e) { "PLANNER_STEP_PARSE_ERROR: invalid JSON from LLM" }
                    return emptyList()
                }
            return when {
                raw.isArray -> raw.map { toPlanStep(it) }
                raw.isObject -> {
                    when {
                        raw.has("name") && raw.has("parameters") -> listOf(toPlanStep(raw))
                        else ->
                            findArrayWithSteps(raw) ?: run {
                                // try nested textual field containing JSON
                                val textField =
                                    raw
                                        .fields()
                                        .asSequence()
                                        .firstOrNull { it.value.isTextual }
                                        ?.value
                                if (textField != null) parseNode(textField.asText(), depth + 1) else emptyList()
                            }
                    }
                }

                raw.isTextual -> parseNode(raw.asText(), depth + 1)
                else -> emptyList()
            }
        }

        val list = parseNode(inputJson)
        return list.asFlow()
    }
}
