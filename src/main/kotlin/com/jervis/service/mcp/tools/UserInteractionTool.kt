package com.jervis.service.mcp.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * UserInteractionTool integrates the agent with the UI to handle interactive questions and answers.
 * It produces either an ASK payload (to display a question) or an OK payload (to confirm answer captured).
 */
@Service
class UserInteractionTool(
    private val taskContextRepo: TaskContextMongoRepository,
    private val language: LanguageOrchestrator,
) : McpTool {
    private val mapper = jacksonObjectMapper()

    override val name: String = "user.await"
    override val description: String =
        "Display a user dialog (ask/confirm); optionally translate question/answer and store a brief summary into context."

    override suspend fun execute(
        context: TaskContextDocument,
        parameters: String,
    ): ToolResult {
        val params: Map<String, Any?> = parseParams(parameters)
        val fast = (params["quick"] as? Boolean) ?: context.quick

        // Answer mode
        val answerRaw = params["answer"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (answerRaw != null) return handleAnswer(context, answerRaw, fast)

        // Ask mode
        val questionRawParam = params["question"]?.toString()?.trim().orEmpty()
        val questionRaw = questionRawParam.ifBlank { pickQuestionFromContext(context) }
        if (questionRaw.isBlank()) {
            return ToolResult.error(output = "Invalid parameters", message = "Missing 'question' or 'answer'")
        }
        val interactionType = params["type"]?.toString()?.lowercase()?.takeIf { it.isNotBlank() } ?: "ask"
        val reason = params["reason"]?.toString()?.takeIf { it.isNotBlank() }

        val targetLang = context.originalLanguage?.takeIf { it.isNotBlank() } ?: "en"
        val localizedQuestion = translateTo(targetLang, questionRaw, fast)

        val updated =
            context.copy(
                contextSummary = "ASK[$targetLang]: $localizedQuestion",
                updatedAt = Instant.now(),
            )
        taskContextRepo.save(updated)

        val payload =
            mapOf(
                "awaitingUser" to true,
                "language" to targetLang,
                "question" to localizedQuestion,
                "type" to interactionType,
                "reason" to reason,
                "context" to
                    mapOf(
                        "contextId" to context.contextId.toHexString(),
                        "clientName" to context.clientName,
                        "projectName" to context.projectName,
                        "initialQuery" to context.initialQuery,
                    ),
            )
        return ToolResult.ask(output = mapper.writeValueAsString(payload))
    }

    private suspend fun handleAnswer(
        context: TaskContextDocument,
        answerRaw: String,
        quick: Boolean,
    ): ToolResult {
        val englishAnswer = translateTo("en", answerRaw, quick)

        val updated =
            context.copy(
                contextSummary = "ANSWER: $englishAnswer",
                updatedAt = Instant.now(),
            )
        taskContextRepo.save(updated)

        val payload =
            mapOf(
                "awaitingUser" to false,
                "accepted" to true,
                "englishAnswer" to englishAnswer,
                "originalAnswer" to answerRaw,
            )
        return ToolResult.ok(output = mapper.writeValueAsString(payload))
    }

    private fun parseParams(parameters: String): Map<String, Any?> =
        try {
            mapper.readValue(parameters)
        } catch (_: Exception) {
            // fallback: treat the whole string as question text
            mapOf("question" to parameters)
        }

    private fun pickQuestionFromContext(context: TaskContextDocument): String = context.contextSummary ?: ""

    private suspend fun translateTo(
        targetLang: String,
        text: String,
        quick: Boolean,
    ): String = language.translate(text = text, targetLang = targetLang, quick = quick)
}
