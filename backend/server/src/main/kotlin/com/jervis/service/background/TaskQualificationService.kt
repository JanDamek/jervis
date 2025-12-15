package com.jervis.service.background

import com.jervis.configuration.prompts.ProviderCapabilitiesService
import com.jervis.configuration.properties.KoogProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.koog.qualifier.KoogQualifierAgent
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.text.TikaTextExtractionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * CPU/GPU qualification service.
 * Prepares robust prompts with One-Shot examples to force 14B models into Tool Use.
 *
 * SINGLETON GUARANTEE:
 * - Only ONE instance of processAllQualifications() can run at a time
 * - Uses atomic flag to prevent concurrent execution
 * - This ensures qualifier agent runs exactly once per application instance
 */
@Service
class TaskQualificationService(
    private val pendingTaskService: PendingTaskService,
    private val koogQualifierAgent: KoogQualifierAgent,
    private val providerCapabilitiesService: ProviderCapabilitiesService,
    private val promptRepository: PromptRepository,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val koogProperties: KoogProperties,
) {
    private val logger = KotlinLogging.logger {}

    // Atomic flag to ensure only one qualification cycle runs at a time
    private val isQualificationRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        // SINGLETON GUARANTEE: Only one qualification cycle can run at a time
        if (!isQualificationRunning.compareAndSet(false, true)) {
            logger.warn {
                "QUALIFICATION_SKIPPED: Another qualification cycle is already running. " +
                "This prevents concurrent qualifier agent execution."
            }
            return
        }

        try {
            logger.debug { "QUALIFICATION_CYCLE_START: Acquired singleton lock" }

            val capabilities = providerCapabilitiesService.getProviderCapabilities(ModelProviderEnum.OLLAMA_QUALIFIER)
            val effectiveConcurrency = (capabilities.maxConcurrentRequests).coerceAtLeast(1)

            pendingTaskService
                .findTasksForQualification()
                .buffer(effectiveConcurrency)
                .flatMapMerge(concurrency = effectiveConcurrency) { task ->
                    flow {
                        runCatching { processOne(task) }
                            .onFailure { e ->
                                val isRetriable = isRetriableError(e)
                                logger.error(e) {
                                    "QUALIFICATION_ERROR: task=${task.id} type=${task.type} retriable=$isRetriable msg=${e.message}"
                                }

                                if (isRetriable) {
                                    // Return to READY_FOR_QUALIFICATION for retry
                                    pendingTaskService.returnToQueue(task.id, koogProperties.qualifierMaxRetries)
                                } else {
                                    // Non-retriable error - mark as ERROR
                                    pendingTaskService.markAsError(task.id, e.message ?: "Unknown error")
                                }
                            }
                        emit(Unit)
                    }
                }.catch { e ->
                    logger.error(e) { "Qualification stream failure: ${e.message}" }
                }.collect()

            logger.info { "QUALIFICATION_CYCLE_COMPLETE: Processing finished, releasing singleton lock" }
        } finally {
            // ALWAYS release the lock, even if exception occurs
            isQualificationRunning.set(false)
        }
    }

    private suspend fun processOne(original: PendingTaskDocument) {
        if (original.type == PendingTaskTypeEnum.DATA_PROCESSING) {
            logger.debug { "QUALIFICATION_SKIP: id=${original.id} type=${original.type} - data processing tasks bypass qualification" }
            return
        }
        var task =
            pendingTaskService.tryClaimForQualification(original.id) ?: run {
                logger.debug { "QUALIFICATION_SKIP: id=${original.id} - task already claimed" }
                return
            }

        task = tikaTextExtractionService.ensureCleanContent(task)

        // Get extraction goal with schema and example
        val extractionGoal = promptRepository.goals[original.type]
        require(extractionGoal != null) { "No extraction goal found for: ${original.type}" }

        val result = koogQualifierAgent.run(task = task)

        logger.info {
            "QUALIFICATION_RESULT: id=${task.id} type=${task.type} completed=${result.completed}"
        }
    }

    private fun isRetriableError(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("connection", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ||
            message.contains("doesn't match any condition on available edges", ignoreCase = true) ||
            message.contains("stuck in node", ignoreCase = true) ||
            e is java.net.SocketTimeoutException ||
            e is java.net.SocketException ||
            e is java.net.ConnectException
    }
}
