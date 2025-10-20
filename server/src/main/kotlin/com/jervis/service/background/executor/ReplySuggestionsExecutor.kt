package com.jervis.service.background.executor

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptGenericConfig
import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.Checkpoint
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.service.background.prompt.ChunkPromptTemplate
import com.jervis.service.background.prompt.ChunkResponseParser
import com.jervis.service.gateway.clients.llm.OllamaClient
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ReplySuggestionsExecutor(
    private val ollamaClient: OllamaClient,
) : BackgroundTaskExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun executeChunk(task: BackgroundTask): ChunkResult {
        logger.info { "REPLY_SUGGESTIONS: Starting chunk for task ${task.id}" }

        val checkpoint = task.checkpoint as? Checkpoint.ThreadClustering
        val processedThreadIds = checkpoint?.processedThreadIds ?: emptySet()

        val waitingOnMeItems = fetchWaitingOnMeItems(task, processedThreadIds)

        if (waitingOnMeItems.isEmpty()) {
            logger.info { "REPLY_SUGGESTIONS: No more items to process" }
            return ChunkResult(
                artifacts = emptyList(),
                checkpoint = Checkpoint.ThreadClustering(processedThreadIds, emptyMap()),
                progressDelta = 1.0 - task.progress,
                nextAction = NextAction.STOP,
            )
        }

        val systemPrompt = ChunkPromptTemplate.buildSystemPrompt()
        val userPrompt =
            ChunkPromptTemplate.buildUserPrompt(
                task,
                buildReplySuggestionsContext(waitingOnMeItems),
            )

        val llmResponse =
            callOllamaForBackgroundTask(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
            )

        val parsedResult = ChunkResponseParser.parse(task.id, llmResponse)

        val newProcessedIds = processedThreadIds + waitingOnMeItems.map { it.id }
        val newCheckpoint =
            Checkpoint.ThreadClustering(
                processedThreadIds = newProcessedIds,
                state = emptyMap(),
            )

        return parsedResult.copy(
            checkpoint = newCheckpoint,
            progressDelta = 0.25,
        )
    }

    private suspend fun fetchWaitingOnMeItems(
        task: BackgroundTask,
        processedThreadIds: Set<String>,
    ): List<WaitingOnMeItem> = emptyList()

    private fun buildReplySuggestionsContext(items: List<WaitingOnMeItem>): String =
        buildString {
            appendLine("Generate draft replies for the following items:")
            appendLine()

            items.forEachIndexed { index, item ->
                appendLine("Item ${index + 1}:")
                appendLine("Source: ${item.source}")
                appendLine("Thread: ${item.threadId}")
                appendLine("Question/Request:")
                appendLine(item.content)
                appendLine()
            }

            appendLine("For each item, provide:")
            appendLine("- Concise draft reply (1-3 sentences)")
            appendLine("- Key points to address")
            appendLine("- Confidence score (0.0-1.0)")
            appendLine("- Mark needs_review: true if confidence < 0.8")
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
                        creativityLevel = CreativityLevel.MEDIUM,
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

    private data class WaitingOnMeItem(
        val id: String,
        val source: String,
        val threadId: String,
        val content: String,
    )
}
