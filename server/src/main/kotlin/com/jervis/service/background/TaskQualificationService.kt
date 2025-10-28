package com.jervis.service.background

import com.jervis.configuration.QualifierProperties
import com.jervis.domain.task.PendingTask
import com.jervis.service.gateway.QualifierLlmGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Service for pre-qualifying pending tasks using small, fast models.
 * Filters out obvious spam/noise, delegates everything else to strong model.
 */
@Service
class TaskQualificationService(
    private val qualifierGateway: QualifierLlmGateway,
    private val pendingTaskService: PendingTaskService,
    private val backgroundTaskGoalsService: BackgroundTaskGoalsService,
    private val props: QualifierProperties,
) {
    private val semaphore = Semaphore(props.concurrency)

    /**
     * Process entire Flow of tasks needing qualification.
     * Uses buffer to limit memory (max 32 tasks buffered at once).
     * Semaphore limits parallel processing (e.g., 8 concurrent).
     * Memory safe: doesn't load all 2000+ tasks into heap at once.
     */
    suspend fun processAllQualifications() {
        logger.debug { "Starting processAllQualifications - querying DB for tasks..." }
        var processedCount = 0

        try {
            pendingTaskService
                .findTasksNeedingQualification(needsQualification = true)
                .buffer(32) // Buffer max 32 tasks from DB at once - memory safe
                .collect { task ->
                    // Launch async job, semaphore controls max concurrent (8)
                    coroutineScope {
                        async {
                            semaphore.withPermit {
                                processedCount++
                                if (processedCount == 1) {
                                    logger.info { "First task received - qualification pipeline started" }
                                }
                                if (processedCount % 100 == 0) {
                                    logger.info { "Qualification progress: $processedCount tasks processed..." }
                                }
                                qualifyTask(task)
                            }
                        }.await()
                    }
                }

            if (processedCount > 0) {
                logger.info { "Qualification batch complete: $processedCount total tasks processed" }
            } else {
                logger.debug { "No tasks found needing qualification" }
            }
        } catch (e: Exception) {
            logger.error(e) { "ERROR in processAllQualifications after $processedCount tasks" }
            throw e
        }
    }

    private suspend fun qualifyTask(task: PendingTask) {
        val startTime = System.currentTimeMillis()
        val taskConfig = backgroundTaskGoalsService.getQualifierPrompts(task.taskType)

        val systemPrompt = taskConfig.qualifierSystemPrompt ?: ""
        val userPromptTemplate = taskConfig.qualifierUserPrompt ?: ""

        try {
            // Truncate large content to fit in qualifier context window
            // qwen2.5:3b has 32k context, leave room for prompts + response
            val maxContentChars = 20_000 // ~5k tokens
            val rawContent = task.content ?: ""
            val truncatedContent =
                if (rawContent.length > maxContentChars) {
                    val truncated = rawContent.take(maxContentChars)
                    logger.debug {
                        "Task ${task.id} content truncated from ${rawContent.length} to $maxContentChars chars for qualification"
                    }
                    truncated + "\n\n[... content truncated for qualification ...]"
                } else {
                    rawContent
                }

            val mappingValues = mapOf("content" to truncatedContent)

            val decision =
                qualifierGateway.qualify(
                    systemPromptTemplate = systemPrompt,
                    userPromptTemplate = userPromptTemplate,
                    mappingValues = mappingValues,
                )

            val duration = System.currentTimeMillis() - startTime

            when (decision) {
                is QualifierLlmGateway.QualifierDecision.Discard -> {
                    logger.info { "Task ${task.id} (${task.taskType}) DISCARDED by qualifier in ${duration}ms - spam/noise" }
                    pendingTaskService.deleteTask(task.id)
                }

                is QualifierLlmGateway.QualifierDecision.Delegate -> {
                    logger.debug { "Task ${task.id} (${task.taskType}) DELEGATED in ${duration}ms to strong model" }
                    pendingTaskService.setNeedsQualification(task.id, false)
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) { "Qualification failed for task ${task.id} after ${duration}ms, delegating to strong model" }
            pendingTaskService.setNeedsQualification(task.id, false)
        }
    }
}
