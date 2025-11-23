package com.jervis.service.background

import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.domain.task.PendingTask
import com.jervis.dto.PendingTaskState
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.repository.ProjectMongoRepository
import com.jervis.repository.ScheduledTaskMongoRepository
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.debug.DebugService
import com.jervis.service.notification.ErrorNotificationsPublisher
import com.jervis.service.scheduling.TaskManagementService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that processes PendingTasks.
 *
 * THREE INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 * 2. Execution loop (GPU) - processes qualified tasks during idle GPU time
 * 3. Scheduler loop - dispatches scheduled tasks 10 minutes before scheduled time
 *
 * STARTUP ORDER:
 * @Order(10) ensures this starts AFTER WeaviateSchemaInitializer (@Order(0))
 * This guarantees vector store schema is ready before any indexing/processing begins.
 */
@Service
@Order(10) // Start after schema initialization
class BackgroundEngine(
    private val llmLoadMonitor: LlmLoadMonitor,
    private val pendingTaskService: PendingTaskService,
    private val agentOrchestrator: AgentOrchestratorService,
    private val taskQualificationService: TaskQualificationService,
    private val backgroundProperties: BackgroundProperties,
    private val errorNotificationsPublisher: ErrorNotificationsPublisher,
    private val debugService: DebugService,
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
    private val taskManagementService: TaskManagementService,
    private val projectMongoRepository: ProjectMongoRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L // 5 minutes
    private val schedulerAdvanceMinutes = 10L // Dispatch tasks 10 minutes before scheduled time

    @PostConstruct
    fun start() {
        logger.info { "BackgroundEngine starting - initializing three independent loops..." }

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

        // Start scheduler loop (10-minute advance dispatch)
        schedulerJob =
            scope.launch {
                try {
                    logger.info { "Scheduler loop STARTED (10-minute interval)" }
                    runSchedulerLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Scheduler loop FAILED to start!" }
                }
            }

        logger.info { "BackgroundEngine initialization complete - all three loops launched" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Background engine stopping..." }
        currentTaskJob.getAndSet(null)?.cancel(CancellationException("Application shutdown"))
        qualificationJob?.cancel()
        executionJob?.cancel()
        schedulerJob?.cancel()
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                withTimeout(3000) {
                    qualificationJob?.join()
                    executionJob?.join()
                    schedulerJob?.join()
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

                // Flow exhausted for this scan
                logger.info { "Qualification cycle complete - sleeping 30s..." }
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
                        logger.info {
                            "GPU_TASK_PICKUP: id=${task.id} correlationId=${task.correlationId} type=${task.taskType} state=${task.state}"
                        }

                        // Publish debug event
                        debugService.gpuTaskPickup(
                            correlationId = task.correlationId,
                            taskId = task.id.toHexString(),
                            taskType = task.taskType.name,
                            state = task.state.name,
                        )

                        executeTask(task) // Blocks until task completes - no other task can start
                        logger.info { "GPU_TASK_FINISHED: id=${task.id} correlationId=${task.correlationId}" }
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
                logger.info { "GPU_EXECUTION_START: id=${task.id} correlationId=${task.correlationId} type=${task.taskType}" }

                try {
                    // All tasks go through agent orchestrator with goals from YAML
                    val response = agentOrchestrator.handleBackgroundTask(task)

                    pendingTaskService.deleteTask(task.id)
                    logger.info { "GPU_EXECUTION_SUCCESS: id=${task.id} correlationId=${task.correlationId}" }

                    // Reset failure counter on success
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    logger.info { "GPU_EXECUTION_INTERRUPTED: id=${task.id} correlationId=${task.correlationId}" }

                    // Save progress context for task resumption
                    try {
                        val progressContext = agentOrchestrator.getLastPlanContext(task.correlationId)
                        if (progressContext != null) {
                            pendingTaskService.appendProgressContext(task.id, progressContext)
                            logger.info { "Saved progress context for interrupted task ${task.id} (${progressContext.length} chars)" }
                        } else {
                            logger.debug { "No progress context to save for interrupted task ${task.id}" }
                        }
                    } catch (saveError: Exception) {
                        logger.error(saveError) { "Failed to save progress context for task ${task.id}" }
                    }

                    // Task remains in DISPATCHED_GPU state and will be retried with progress context
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
                        "GPU_EXECUTION_FAILED: id=${task.id} correlationId=${task.correlationId} errorType=$errorType " +
                            "consecutiveFailures=$consecutiveFailures isCommunication=$isCommunicationError"
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

    /**
     * Scheduler loop - dispatches scheduled tasks 10 minutes before their scheduled time.
     * Runs every 10 minutes, finds tasks scheduled within next 10 minutes, and creates pending tasks.
     */
    private suspend fun runSchedulerLoop() {
        logger.info { "Scheduler loop entering main loop..." }

        while (scope.isActive) {
            try {
                val now = Instant.now()
                val advanceWindow = Duration.ofMinutes(schedulerAdvanceMinutes)
                val windowEnd = now.plus(advanceWindow)

                logger.debug { "Scheduler: checking tasks scheduled between $now and $windowEnd" }

                // Find tasks scheduled within next 10 minutes
                val upcomingTasks =
                    scheduledTaskRepository
                        .findTasksScheduledBetween(now, windowEnd)
                        .toList()

                if (upcomingTasks.isNotEmpty()) {
                    logger.info { "Scheduler: found ${upcomingTasks.size} upcoming task(s) to dispatch" }

                    upcomingTasks.forEach { task ->
                        try {
                            // Create pending task with correlationId linking back to scheduled task
                            pendingTaskService.createTask(
                                taskType = PendingTaskTypeEnum.SCHEDULED_TASK,
                                content = task.content, // Agent gets full context directly
                                clientId = task.clientId,
                                projectId = task.projectId,
                                correlationId = task.correlationId ?: task.id.toHexString(),
                            )

                            // Handle lifecycle based on cron
                            if (task.cronExpression != null) {
                                // Recurring task - calculate next occurrence and update scheduledAt
                                try {
                                    val cron = CronExpression.parse(task.cronExpression)
                                    val nextOccurrence = cron.next(ZonedDateTime.ofInstant(task.scheduledAt, ZoneId.systemDefault()))

                                    if (nextOccurrence != null) {
                                        taskManagementService.updateScheduledTime(task.id, nextOccurrence.toInstant())
                                        logger.info {
                                            "Scheduler: dispatched recurring task '${task.taskName}', next occurrence: $nextOccurrence"
                                        }
                                    } else {
                                        logger.warn {
                                            "Scheduler: no next occurrence for recurring task '${task.taskName}', deleting"
                                        }
                                        scheduledTaskRepository.deleteById(task.id)
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) {
                                        "Scheduler: failed to parse cron expression '${task.cronExpression}' for task '${task.taskName}', deleting task"
                                    }
                                    scheduledTaskRepository.deleteById(task.id)
                                }
                            } else {
                                // One-time task - delete after dispatch
                                scheduledTaskRepository.deleteById(task.id)
                                logger.info {
                                    "Scheduler: dispatched one-time task '${task.taskName}' (scheduled for ${task.scheduledAt}), task deleted"
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Scheduler: failed to dispatch task ${task.id}" }
                        }
                    }
                } else {
                    logger.debug { "Scheduler: no upcoming tasks in next $schedulerAdvanceMinutes minutes" }
                }

                // Sleep 10 minutes before next check
                delay(advanceWindow.toMillis())
            } catch (e: CancellationException) {
                logger.info { "Scheduler loop cancelled" }
                throw e
            } catch (e: Exception) {
                val waitMs = backgroundProperties.waitOnError.toMillis()
                logger.error(e) { "ERROR in scheduler loop - will retry in ${waitMs / 1000}s (configured)" }
                delay(waitMs)
            }
        }

        logger.warn { "Scheduler loop exited - scope is no longer active" }
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
