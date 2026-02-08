package com.jervis.service.background

import com.jervis.entity.TaskDocument
import com.jervis.qualifier.SimpleQualifierAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Qualification service - sends tasks to KB microservice for indexing.
 *
 * Retry strategy (DB-based exponential backoff):
 * - Operational errors (timeout, connection refused): retry with backoff 1s→2s→4s→...→5min, then 5min forever
 * - Actual indexing errors: mark as ERROR permanently (no retry)
 * - Retry state is in DB (nextQualificationRetryAt), NOT in RAM
 *
 * Concurrency: 4 parallel KB requests to improve throughput during bulk indexing.
 */
@Service
class TaskQualificationService(
    private val taskService: TaskService,
    private val simpleQualifierAgent: SimpleQualifierAgent,
) {
    private val logger = KotlinLogging.logger {}

    private val isQualificationRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        if (!isQualificationRunning.compareAndSet(false, true)) {
            logger.debug { "QUALIFICATION_SKIPPED: Another cycle already running" }
            return
        }

        try {
            logger.debug { "QUALIFICATION_CYCLE_START" }

            val effectiveConcurrency = 4

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
                                    // Operational error → return to queue with exponential backoff (never marks ERROR)
                                    taskService.returnToQueue(task)
                                } else {
                                    // Actual indexing error → permanent ERROR
                                    taskService.markAsError(task, e.message ?: "Unknown error")
                                }
                            }
                        emit(Unit)
                    }
                }.catch { e ->
                    logger.error(e) { "Qualification stream failure: ${e.message}" }
                }.collect()

            logger.info { "QUALIFICATION_CYCLE_COMPLETE" }
        } finally {
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
            message.contains("prematurely closed", ignoreCase = true) ||
            e is java.net.SocketTimeoutException ||
            e is java.net.SocketException
    }
}
