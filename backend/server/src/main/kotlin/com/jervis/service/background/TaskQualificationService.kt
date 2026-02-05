package com.jervis.service.background

import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.TaskDocument
import com.jervis.koog.qualifier.SimpleQualifierAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Simplified qualification service.
 *
 * Delegates all heavy lifting to KB microservice:
 * - Text extraction (Tika)
 * - Vision/OCR (qwen3-vl)
 * - RAG indexing
 * - Graph creation
 * - Summary generation for routing
 *
 * Routes based on KB's hasActionableContent flag:
 * - true → READY_FOR_GPU (user action required)
 * - false → DONE (just indexed)
 *
 * SINGLETON GUARANTEE:
 * - Only ONE instance of processAllQualifications() can run at a time
 * - Uses an atomic flag to prevent concurrent execution
 */
@Service
class TaskQualificationService(
    private val taskService: TaskService,
    private val simpleQualifierAgent: SimpleQualifierAgent,
    private val koogProperties: KoogProperties,
) {
    private val logger = KotlinLogging.logger {}

    // Atomic flag to ensure only one qualification cycle runs at a time
    private val isQualificationRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

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
            logger.debug { "QUALIFICATION_CYCLE_START: Acquired singleton lock (simplified)" }

            // Process tasks sequentially - KB microservice handles the heavy lifting
            // Concurrency is limited since we're just proxying to KB
            val effectiveConcurrency = 2

            taskService
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
                                    taskService.returnToQueue(task, koogProperties.qualifierMaxRetries)
                                } else {
                                    // Non-retriable error - mark as ERROR
                                    taskService.markAsError(task, e.message ?: "Unknown error")
                                }
                            }
                        emit(Unit)
                    }
                }.catch { e ->
                    logger.error(e) { "Qualification stream failure: ${e.message}" }
                }.collect()

            logger.info { "QUALIFICATION_CYCLE_COMPLETE: Processing finished, releasing singleton lock" }
        } finally {
            // ALWAYS release the lock, even if an exception occurs
            isQualificationRunning.set(false)
        }
    }

    private suspend fun processOne(original: TaskDocument) {
        val task =
            taskService.setToQualifying(original) ?: run {
                logger.debug { "QUALIFICATION_SKIP: id=${original.id} - task already claimed" }
                return
            }

        val summary = simpleQualifierAgent.run(task)

        logger.info {
            "QUALIFICATION_RESULT: id=${task.id} type=${task.type} summary=${summary.take(100)}"
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
            e is java.net.SocketException
    }
}
