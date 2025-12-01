package com.jervis.service.background

import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
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
 * 3. Scheduler loop - dispatches scheduled tasks 10 minutes before the scheduled time
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
    private val backgroundProperties: BackgroundProperties,
    private val errorNotificationsPublisher: ErrorNotificationsPublisher,
    private val debugService: DebugService,
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
    private val taskManagementService: TaskManagementService,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L
    private val schedulerAdvanceMinutes = 10L

    @PostConstruct
    fun start() {
        logger.info { "BackgroundEngine starting - initializing three independent loops..." }

        qualificationJob =
            scope.launch {
                try {
                    logger.info { "Qualification loop STARTED (CPU, independent)" }
                    runQualificationLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Qualification loop FAILED to start!" }
                }
            }

        executionJob =
            scope.launch {
                try {
                    logger.info { "Execution loop STARTED (GPU, idle-based)" }
                    runExecutionLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Execution loop FAILED to start!" }
                }
            }

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
     * Qualification loop - runs continuously on CPU, independent of the GPU state.
     * Processes the entire Flow of tasks needing qualification using concurrency limit.
     * Simple: load Flow, process all with semaphore, wait the 30s if nothing, repeat.
     */
    private suspend fun runQualificationLoop() {
        logger.info { "Qualification loop entering main loop..." }

        while (scope.isActive) {
            try {
                logger.debug { "Qualification loop: starting processAllQualifications..." }

                // Process all tasks in Flow with concurrency limit (e.g., 8 parallel)
                // TODO: Re-enable after email refactoring
                // taskQualificationService.processAllQualifications()

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
                    val task =
                        pendingTaskService
                            .findTasksByState(PendingTaskStateEnum.DISPATCHED_GPU)
                            .firstOrNull()

                    if (task != null) {
                        logger.info {
                            "GPU_TASK_PICKUP: id=${task.id} correlationId=${task.correlationId} type=${task.type} state=${task.state}"
                        }

                        debugService.gpuTaskPickup(
                            correlationId = task.correlationId,
                            taskId = task.id.toHexString(),
                            taskType = task.type,
                            state = task.state,
                        )

                        executeTask(task)
                        logger.info { "GPU_TASK_FINISHED: id=${task.id} correlationId=${task.correlationId}" }
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

    private suspend fun executeTask(task: PendingTaskDocument) {
        val taskJob =
            scope.launch {
                logger.info { "GPU_EXECUTION_START: id=${task.id} correlationId=${task.correlationId} type=${task.type}" }

                try {
                    agentOrchestrator.handleBackgroundTask(task)
                    pendingTaskService.deleteTask(task.id)
                    logger.info { "GPU_EXECUTION_SUCCESS: id=${task.id} correlationId=${task.correlationId}" }

                    consecutiveFailures = 0
                } catch (_: CancellationException) {
                    logger.info { "GPU_EXECUTION_INTERRUPTED: id=${task.id} correlationId=${task.correlationId}" }

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
                } catch (e: Exception) {
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

                    try {
                        if (isCommunicationError) {
                            val errorMessage =
                                "Background task failed (${task.type}): $errorType - ${e.message}"
                            errorNotificationsPublisher.publishError(
                                message = errorMessage,
                                stackTrace = e.stackTraceToString(),
                                correlationId = task.id.toHexString(),
                            )
                            logger.info { "Published LLM error to notifications for task ${task.id}" }
                            pendingTaskService.deleteTask(task.id)
                        } else {
                            pendingTaskService.failAndEscalateToUserTask(task, reason = errorType, error = e)
                        }
                    } catch (esc: Exception) {
                        logger.error(esc) { "Failed to handle task error for ${task.id}" }
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
     * Runs every 10 minutes, finds tasks scheduled within the next 10 minutes, and creates pending tasks.
     */
    private suspend fun runSchedulerLoop() {
        logger.info { "Scheduler loop entering main loop..." }

        while (scope.isActive) {
            try {
                val now = Instant.now()
                val advanceWindow = Duration.ofMinutes(schedulerAdvanceMinutes)
                val windowEnd = now.plus(advanceWindow)

                logger.debug { "Scheduler: checking tasks scheduled between $now and $windowEnd" }

                val upcomingTasks =
                    scheduledTaskRepository
                        .findTasksScheduledBetween(now, windowEnd)
                        .toList()

                if (upcomingTasks.isNotEmpty()) {
                    logger.info { "Scheduler: found ${upcomingTasks.size} upcoming task(s) to dispatch" }

                    upcomingTasks.forEach { task ->
                        try {
                            pendingTaskService.createTask(
                                taskType = PendingTaskTypeEnum.SCHEDULED_TASK,
                                content = task.content,
                                clientId = task.clientId,
                                projectId = task.projectId,
                                correlationId = task.correlationId ?: task.id.toHexString(),
                            )

                            if (task.cronExpression != null) {
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
         * Called by LlmLoadMonitor when transitioning to a busy state.
         */
        fun interruptNow() {
            currentTaskJob.getAndSet(null)?.cancel(CancellationException("Foreground request"))
        }
    }
}
