package com.jervis.service.background

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskState
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that processes PendingTasks.
 *
 * TWO INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 * 2. Execution loop (GPU) - processes qualified tasks during idle GPU time
 */
@Service
class BackgroundEngine(
    private val llmLoadMonitor: LlmLoadMonitor,
    private val pendingTaskService: PendingTaskService,
    private val agentOrchestrator: AgentOrchestratorService,
    private val taskQualificationService: TaskQualificationService,
    private val backgroundProperties: com.jervis.configuration.properties.BackgroundProperties,
    private val errorNotificationsPublisher: com.jervis.service.notification.ErrorNotificationsPublisher,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L // 5 minutes

    @PostConstruct
    fun start() {
        logger.info { "BackgroundEngine starting - initializing qualification and execution loops..." }

        // Start qualification loop (CPU, independent)
        qualificationJob =
            scope.launch {
                try {
                    logger.info { "Qualification loop STARTED (CPU, independent)" }
                    runQualificationLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Qualification loop FAILED to start!" }
                }
            }

        // Start execution loop (GPU, idle-based)
        executionJob =
            scope.launch {
                try {
                    logger.info { "Execution loop STARTED (GPU, idle-based)" }
                    runExecutionLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Execution loop FAILED to start!" }
                }
            }

        logger.info { "BackgroundEngine initialization complete - both loops launched" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Background engine stopping..." }
        currentTaskJob.getAndSet(null)?.cancel(CancellationException("Application shutdown"))
        qualificationJob?.cancel()
        executionJob?.cancel()
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                withTimeout(3000) {
                    qualificationJob?.join()
                    executionJob?.join()
                }
            }
        } catch (_: Exception) {
            logger.debug { "Background engine shutdown timeout" }
        }
    }

    /**
     * Qualification loop - runs continuously on CPU, independent of GPU state.
     * Processes entire Flow of tasks needing qualification using concurrency limit.
     * Simple: load Flow, process all with semaphore, wait 30s if nothing, repeat.
     */
    private suspend fun runQualificationLoop() {
        logger.info { "Qualification loop entering main loop..." }

        while (scope.isActive) {
            try {
                logger.debug { "Qualification loop: starting processAllQualifications..." }

                // Process all tasks in Flow with concurrency limit (e.g., 8 parallel)
                taskQualificationService.processAllQualifications()

                // If we get here, Flow is exhausted - no more tasks
                logger.info { "Qualification cycle complete - no more tasks, sleeping 30s..." }
                delay(30_000)
            } catch (e: CancellationException) {
                logger.info { "Qualification loop cancelled" }
                throw e
            } catch (e: Exception) {
                val waitMs = backgroundProperties.waitOnError.toMillis()
                logger.error(e) { "ERROR in qualification loop - will retry in ${waitMs / 1000}s (configured)" }
                delay(waitMs)
            }
        }

        logger.warn { "Qualification loop exited - scope is no longer active" }
    }

    /**
     * Execution loop - processes qualified tasks (needsQualification = false) during idle GPU time.
     * Waits for GPU to be idle before running strong model tasks.
     */
    private suspend fun runExecutionLoop() {
        while (scope.isActive) {
            try {
                val activeRequests = llmLoadMonitor.getActiveRequestCount()
                val idleDuration = llmLoadMonitor.getIdleDuration()

                logger.debug { "Execution loop: activeRequests=$activeRequests, idleDuration=${idleDuration.seconds}s" }

                // Check if GPU is available for strong model tasks
                // If no foreground activity, run background tasks immediately,
                // Idle threshold only applies when there are active foreground requests
                val canRunBackgroundTask =
                    if (activeRequests == 0) {
                        logger.debug { "No active foreground requests, background can run immediately" }
                        true
                    } else {
                        val idleThreshold = Duration.ofSeconds(30)
                        val isIdle = llmLoadMonitor.isIdleFor(idleThreshold)
                        if (isIdle) {
                            logger.debug {
                                "Foreground idle for ${idleDuration.seconds}s (>${idleThreshold.seconds}" +
                                    "s threshold), background can run"
                            }
                        }
                        isIdle
                    }

                if (canRunBackgroundTask) {
                    // Get next task for strong model (state = DISPATCHED_GPU)
                    val task =
                        pendingTaskService
                            .findTasksByState(PendingTaskState.DISPATCHED_GPU)
                            .firstOrNull()

                    if (task != null) {
                        logger.info { "Found pending task ${task.id} (${task.taskType}), starting execution..." }
                        executeTask(task) // Blocks until task completes - no other task can start
                        logger.info { "Task execution finished, checking for next task..." }
                        // Loop immediately to check for next task (no delay)
                    } else {
                        logger.debug { "No qualified tasks found, sleeping 30s..." }
                        delay(30_000)
                    }
                } else {
                    logger.debug {
                        "GPU not idle yet (activeRequests=$activeRequests, idle=${idleDuration.seconds}" +
                            "s < 30s), waiting 10s..."
                    }
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                logger.info { "Execution loop cancelled" }
                throw e
            } catch (e: Exception) {
                val waitMs = backgroundProperties.waitOnError.toMillis()
                logger.error(e) { "Error in execution loop - will retry in ${waitMs / 1000}s (configured)" }
                delay(waitMs)
            }
        }
    }

    private suspend fun executeTask(task: PendingTask) {
        val taskJob =
            scope.launch {
                logger.info { "Starting pending task: ${task.id} (${task.taskType})" }

                try {
                    // All tasks go through agent orchestrator with goals from YAML
                    agentOrchestrator.handleBackgroundTask(task)

                    pendingTaskService.deleteTask(task.id)
                    logger.info { "Task completed and deleted: ${task.id}" }

                    // Reset failure counter on success
                    consecutiveFailures = 0
                } catch (_: CancellationException) {
                    logger.info { "Task interrupted: ${task.id}" }
                } catch (e: Exception) {
                    // Classify error type for appropriate handling
                    val errorType =
                        when {
                            e.message?.contains("Connection prematurely closed") == true -> "LLM_CONNECTION_FAILED"
                            e.message?.contains("LLM call failed") == true -> "LLM_UNAVAILABLE"
                            e.message?.contains("timeout", ignoreCase = true) == true -> "LLM_TIMEOUT"
                            e.message?.contains("Connection refused") == true -> "LLM_UNREACHABLE"
                            e is java.net.SocketException -> "NETWORK_ERROR"
                            e is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
                            else -> "TASK_EXECUTION_ERROR"
                        }

                    // Only increment consecutive failures for communication errors
                    val isCommunicationError =
                        errorType in
                            setOf(
                                "LLM_CONNECTION_FAILED",
                                "LLM_UNAVAILABLE",
                                "LLM_TIMEOUT",
                                "LLM_UNREACHABLE",
                                "NETWORK_ERROR",
                                "NETWORK_TIMEOUT",
                            )

                    if (isCommunicationError) {
                        consecutiveFailures++
                    } else {
                        // Non-communication errors don't affect backoff (logic errors, data issues, etc.)
                        logger.warn { "Non-communication error detected, not incrementing failure counter" }
                    }

                    val backoffDelay =
                        if (isCommunicationError) {
                            minOf(30_000L * consecutiveFailures, maxRetryDelay)
                        } else {
                            0L // No delay for logic errors - continue immediately
                        }

                    logger.error(e) {
                        "Task failed: ${task.id}, errorType=$errorType, " +
                            "consecutiveFailures=$consecutiveFailures, isCommunication=$isCommunicationError"
                    }

                    // Handle error based on type:
                    // - Communication/LLM errors: publish to error notifications (desktop popup)
                    // - Logic/data errors: escalate to user task (requires human intervention)
                    try {
                        if (isCommunicationError) {
                            // LLM/network errors go to error notifications
                            val errorMessage =
                                "Background task failed (${task.taskType.name}): $errorType - ${e.message}"
                            errorNotificationsPublisher.publishError(
                                message = errorMessage,
                                stackTrace = e.stackTraceToString(),
                                correlationId = task.id.toHexString(),
                            )
                            logger.info { "Published LLM error to notifications for task ${task.id}" }
                            // Delete the pending task - it will be retried by next background cycle
                            pendingTaskService.deleteTask(task.id)
                        } else {
                            // Logic errors require human intervention - create user task
                            pendingTaskService.failAndEscalateToUserTask(task, reason = errorType, error = e)
                        }
                    } catch (esc: Exception) {
                        logger.error(esc) { "Failed to handle task error for ${task.id}" }
                        // Ensure deletion to avoid retry loop even if error handling fails
                        pendingTaskService.deleteTask(task.id)
                    }

                    if (backoffDelay > 0) {
                        logger.warn {
                            "Communication error detected, backing off for ${backoffDelay / 1000}s before next attempt"
                        }
                        delay(backoffDelay)
                    } else {
                        logger.info { "Continuing immediately to next task (non-communication error)" }
                    }
                }
            }

        currentTaskJob.set(taskJob)
        taskJob.join()
        currentTaskJob.compareAndSet(taskJob, null)
    }

    companion object {
        private val currentTaskJob = AtomicReference<Job?>(null)

        /**
         * Immediately interrupts the currently running background task.
         * Called by LlmLoadMonitor when transitioning to busy state.
         */
        fun interruptNow() {
            currentTaskJob.getAndSet(null)?.cancel(CancellationException("Foreground request"))
        }
    }
}
