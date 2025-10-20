package com.jervis.service.background.executor

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptGenericConfig
import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.Checkpoint
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.background.prompt.ChunkPromptTemplate
import com.jervis.service.background.prompt.ChunkResponseParser
import com.jervis.service.gateway.clients.llm.OllamaClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class RagGapDiscoveryExecutor(
    private val vectorStorage: VectorStorageRepository,
    private val ollamaClient: OllamaClient,
) : BackgroundTaskExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun executeChunk(task: BackgroundTask): ChunkResult {
        logger.info { "RAG_GAP_DISCOVERY: Starting chunk for task ${task.id}" }

        val checkpoint = task.checkpoint as? Checkpoint.Generic
        val cursor = checkpoint?.cursor?.toIntOrNull() ?: 0

        val chunkData = fetchSemanticPassages(task, cursor).toList()

        if (chunkData.isEmpty()) {
            logger.info { "RAG_GAP_DISCOVERY: No more data to process" }
            return ChunkResult(
                artifacts = emptyList(),
                checkpoint = Checkpoint.Generic(cursor = null, notes = "completed"),
                progressDelta = 1.0 - task.progress,
                nextAction = NextAction.STOP,
            )
        }

        val systemPrompt = ChunkPromptTemplate.buildSystemPrompt()
        val userPrompt =
            ChunkPromptTemplate.buildUserPrompt(
                task,
                buildGapDiscoveryContext(chunkData),
            )

        val llmResponse =
            callOllamaForBackgroundTask(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
            )

        val parsedResult = ChunkResponseParser.parse(task.id, llmResponse)

        val newCursor = cursor + chunkData.size
        val newCheckpoint = Checkpoint.Generic(cursor = newCursor.toString(), notes = "processing")

        return parsedResult.copy(
            checkpoint = newCheckpoint,
            progressDelta = 0.25,
        )
    }

    private suspend fun fetchSemanticPassages(
        task: BackgroundTask,
        offset: Int,
    ): Flow<SemanticPassage> {
        val projectId = task.targetRef.id

        return vectorStorage
            .searchByFilter(
                mapOf("projectId" to projectId),
                limit = 10,
                offset = offset,
            ).take(10)
            .map { doc ->
                SemanticPassage(
                    content = doc.summary,
                    source = "${doc.ragSourceType}:${doc.className ?: doc.packageName ?: "unknown"}",
                    type = doc.ragSourceType.name,
                )
            }.catch { e ->
                logger.error(e) { "Failed to fetch semantic passages" }
            }
    }

    private fun buildGapDiscoveryContext(passages: List<SemanticPassage>): String =
        buildString {
            appendLine("Analyze the following semantic passages to identify knowledge gaps:")
            appendLine()

            passages.forEachIndexed { index, passage ->
                appendLine("Passage ${index + 1} [${passage.type}]:")
                appendLine("Source: ${passage.source}")
                appendLine(passage.content)
                appendLine()
            }

            appendLine("Identify:")
            appendLine("1. Unanswered questions or incomplete information")
            appendLine("2. Missing documentation or code examples")
            appendLine("3. Contradictory or unclear statements")
            appendLine("4. Areas requiring deeper explanation")
            appendLine()
            appendLine("For each gap, provide:")
            appendLine("- Brief description of the gap")
            appendLine("- Affected source reference")
            appendLine("- Confidence score (0.0-1.0)")
        }

    private suspend fun callOllamaForBackgroundTask(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val config =
            ModelsProperties.ModelDetail(
                model = "qwen3-coder:30b",
                provider = ModelProvider.OLLAMA,
                contextLength = 32768,
                numPredict = 4096,
            )

        val prompt =
            PromptGenericConfig(
                systemPrompt = systemPrompt,
                userPrompt = "",
                modelParams =
                    ModelParams(
                        modelType = ModelType.GENERIC_TEXT_MODEL,
                        creativityLevel = CreativityLevel.LOW,
                    ),
            )

        val estimatedTokens = (systemPrompt.length + userPrompt.length) / 4

        val response =
            ollamaClient.call(
                model = config.model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                config = config,
                prompt = prompt,
                estimatedTokens = estimatedTokens,
            )

        return response.answer
    }

    private data class SemanticPassage(
        val content: String,
        val source: String,
        val type: String,
    )
}
