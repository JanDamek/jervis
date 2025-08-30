package com.jervis.service.agent.finalizer

import com.jervis.dto.ChatResponse
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Finalizer produces the final user-facing response based on the task context.
 */
@Service
class FinalizerImpl(
    private val language: LanguageOrchestrator,
    private val taskContextRepo: TaskContextMongoRepository,
) : Finalizer {
    private val logger = KotlinLogging.logger {}

    override suspend fun finalize(contextId: ObjectId): ChatResponse {
        val context = taskContextRepo.findByContextId(contextId) ?: return ChatResponse("Unknown context")

        val baseEnglish = (context.finalResult ?: context.contextSummary ?: "No execution output available.").trim()
        val userLang = context.originalLanguage?.takeIf { it.isNotBlank() } ?: "en"
        val contextBlock =
            buildString {
                appendLine("client=${context.clientName ?: "unknown"}")
                appendLine("project=${context.projectName ?: "unknown"}")
                appendLine("initialQuery=${context.initialQuery}")
            }

        val systemPrompt = (
            """
            You are a Finalizer assistant. Produce one clear and unambiguous answer for the user.
            - Be concise and actionable.
            - If a direct answer is possible, state it immediately.
            - Summarize key reasoning or steps only if necessary.
            - Do not mention internal tools, steps, or planning.
            """.trimIndent()
        )

        val userPrompt = (
            """
            User language (ISO-639-1): $userLang
            Answer strictly in this language.

            Context:
            $contextBlock

            Execution output (English):
            $baseEnglish

            Write the final answer for the user.
            """.trimIndent()
        )

        val finalMessage =
            try {
                com.jervis.domain.model.ModelType.CHAT_EXTERNAL.let { mt ->
                    language
                        .generate(
                            type = mt,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            outputLanguage = userLang,
                            quick = context.quick,
                        ).trim()
                }
            } catch (e: Exception) {
                logger.warn(e) { "FINALIZER_LLM_FAIL: Falling back to passthrough message." }
                baseEnglish
            }.ifBlank { baseEnglish }

        return ChatResponse(
            message = finalMessage,
            detectedClient = context.clientName,
            detectedProject = context.projectName,
            englishText = baseEnglish,
        )
    }
}
