package com.jervis.service.background

import com.jervis.entity.TaskDocument
import com.jervis.qualifier.SimpleQualifierAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Qualification service — dispatches tasks to KB microservice for async processing.
 *
 * Flow (fire-and-forget):
 * 1. Claim INDEXING tasks (atomic MongoDB update → QUALIFYING)
 * 2. Extract text + load attachments (local, fast)
 * 3. Submit to KB's /ingest/full/async endpoint (returns immediately with HTTP 202)
 * 4. Move on to next task — KB processes in background
 * 5. KB calls /internal/kb-done when finished → KbResultRouter handles routing
 *
 * Since dispatch is fast (no blocking on KB), concurrency=1 is sufficient.
 * Each dispatch takes seconds (Tika extraction + HTTP POST), not minutes.
 *
 * Error handling:
 * - If KB is unreachable or rejects the request → return to queue with backoff
 * - KB handles its own retry logic internally (Ollama busy, timeouts, etc.)
 * - When KB permanently fails, it calls /internal/kb-done with status="error"
 */
@Service
class TaskQualificationService(
    private val taskService: TaskService,
    private val simpleQualifierAgent: SimpleQualifierAgent,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
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

            var dispatchedCount = 0

            taskService
                .findTasksForQualification()
                .buffer(1)
                .flatMapMerge(concurrency = 1) { task ->
                    flow {
                        runCatching { processOne(task) }
                            .onFailure { e ->
                                try {
                                    logger.error(e) {
                                        "QUALIFICATION_DISPATCH_ERROR: task=${task.id} type=${task.type} msg=${e.message}"
                                    }
                                    // KB is unreachable or rejected the request → return to queue
                                    taskService.returnToQueue(task)
                                } catch (inner: Exception) {
                                    logger.error(inner) {
                                        "QUALIFICATION_ERROR_HANDLER_FAILED: task=${task.id} — stuck in QUALIFYING until recovery"
                                    }
                                }
                            }
                        emit(Unit)
                    }
                }.onEach { dispatchedCount++ }
                .catch { e ->
                    logger.error(e) { "Qualification stream failure: ${e.message}" }
                }.collect()

            // Recover tasks stuck in QUALIFYING >10min (e.g. KB callback never arrived)
            taskService.recoverStuckIndexingTasks()

            if (dispatchedCount > 0) {
                logger.info { "QUALIFICATION_CYCLE_COMPLETE: dispatched=$dispatchedCount" }
            }
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

        // Dispatch to KB (fire-and-forget) — task stays in QUALIFYING until KB calls back
        simpleQualifierAgent.dispatch(task) { message, metadata ->
            val step = metadata["step"] ?: "unknown"

            // Persist step to DB for history
            taskService.appendQualificationStep(
                task.id,
                com.jervis.entity.QualificationStepRecord(
                    timestamp = java.time.Instant.now(),
                    step = step,
                    message = message,
                    metadata = metadata,
                ),
            )

            // Emit to live event stream for real-time UI updates
            notificationRpc.emitQualificationProgress(
                taskId = task.id.toString(),
                clientId = task.clientId.toString(),
                message = message,
                step = step,
                metadata = metadata,
            )
        }

        logger.info { "QUALIFICATION_DISPATCHED: id=${task.id} type=${task.type}" }
    }
}
