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
import com.jervis.service.task.UserTaskService
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
import kotlinx.coroutines.time.delay
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
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Qualifier structures data → routes to DONE or READY_FOR_GPU
 * - GPU tasks processed only during idle time (no user requests)
 * - Preemption: User requests immediately interrupt background tasks
 *
 * THREE INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 *    - Creates Graph nodes and RAG chunks with chunking for large documents
 *    - Routes tasks: DONE (simple) or READY_FOR_GPU (complex)
 *
 * 2. Execution loop (GPU) - processes qualified tasks during idle GPU time
 *    - Only runs when no active user requests (checked via LlmLoadMonitor)
 *    - Process READY_FOR_GPU tasks through KoogWorkflowAgent
 *    - Loads TaskMemory context from Qualifier for efficient execution
 *    - Preemption: Interrupted immediately when user request arrives
 *
 * 3. Scheduler loop - dispatches scheduled tasks 10 minutes before the scheduled time
 *
 * PREEMPTION LOGIC:
 * - LlmLoadMonitor tracks active foreground (user) requests
 * - When user request starts: registerRequestStart() → interruptNow()
 * - interruptNow() cancels currently running background task
 * - Background tasks resume only after an idle threshold (30s with no activity)
 * - This ensures user requests ALWAYS get priority over background tasks
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
    private val taskQualificationService: TaskQualificationService,
    private val agentOrchestrator: AgentOrchestratorService,
    private val backgroundProperties: BackgroundProperties,
    private val errorNotificationsPublisher: ErrorNotificationsPublisher,
    private val debugService: DebugService,
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
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

    // Atomic flag to ensure @PostConstruct is called only once
    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    @PostConstruct
    fun start() {
        // SINGLETON GUARANTEE: Prevent multiple initialization (defensive)
        if (!isInitialized.compareAndSet(false, true)) {
            logger.error {
                "BackgroundEngine.start() called multiple times! This should never happen. " +
                "Ignoring duplicate initialization to prevent multiple qualifier agent instances."
            }
            return
        }

        logger.info { "BackgroundEngine starting - initializing three independent loops..." }

        qualificationJob =
            scope.launch {
                try {
                    logger.info { "Qualification loop STARTED (CPU, independent) - SINGLETON GUARANTEED" }
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

        logger.info { "BackgroundEngine initialization complete - all three loops launched with singleton guarantee" }
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
     *
     * SINGLETON GUARANTEE:
     * - This method is called ONLY ONCE from @PostConstruct start()
     * - BackgroundEngine is a @Service singleton managed by Spring
     * - isInitialized flag prevents duplicate start() calls (defensive)
     * - processAllQualifications() has its own singleton lock (isQualificationRunning)
     * - Each task uses tryClaimForQualification() atomic operation
     *
     * RESULT: Only ONE qualifier agent instance runs per application instance, guaranteed at 3 levels:
     * 1. Spring @Service singleton
     * 2. BackgroundEngine.isInitialized flag
     * 3. TaskQualificationService.isQualificationRunning flag
     */
    private suspend fun runQualificationLoop() {
        logger.info { "Qualification loop entering main loop (SINGLETON GUARANTEED at 3 levels)..." }

        while (scope.isActive) {
            delay(backgroundProperties.waitOnStartup)
            try {
                logger.debug { "Qualification loop: starting processAllQualifications..." }
                taskQualificationService.processAllQualifications()
                logger.info { "Qualification cycle complete - sleeping 30s..." }
                delay(backgroundProperties.waitInterval)
            } catch (e: CancellationException) {
                logger.info { "Qualification loop cancelled" }
                throw e
            } catch (e: Exception) {
                val waitMs = backgroundProperties.waitOnError
                logger.error(e) { "ERROR in qualification loop - will retry in waitMs (configured)" }
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
                            taskId = task.id.toString(),
                            taskType = task.type,
                            state = task.state,
                        )

                        executeTask(task)
                        logger.info { "GPU_TASK_FINISHED: id=${task.id} correlationId=${task.correlationId}" }
                        // Task finished - immediately check for next task without delay
                    } else {
                        logger.debug { "No qualified tasks found, sleeping 30s..." }
                        delay(30_000)
                    }
                } else {
                    logger.debug {
                        "GPU not idle yet (activeRequests=$activeRequests, idle=${idleDuration.seconds}" +
                            "s < 30s), waiting 1s..."
                    }
                    delay(1_000)
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
                    agentOrchestrator.run(task, "")
                    pendingTaskService.deleteTask(task.id)
                    logger.info { "GPU_EXECUTION_SUCCESS: id=${task.id} correlationId=${task.correlationId}" }

                    consecutiveFailures = 0
                } catch (_: CancellationException) {
                    logger.info { "GPU_EXECUTION_INTERRUPTED: id=${task.id} correlationId=${task.correlationId}" }
                } catch (e: Exception) {
                    val errorType =
                        when {
                            e.message?.contains("ConnectionDocument prematurely closed") == true -> "LLM_CONNECTION_FAILED"
                            e.message?.contains("LLM call failed") == true -> "LLM_UNAVAILABLE"
                            e.message?.contains("timeout", ignoreCase = true) == true -> "LLM_TIMEOUT"
                            e.message?.contains("ConnectionDocument refused") == true -> "LLM_UNREACHABLE"
                            e is java.net.SocketException -> "NETWORK_ERROR"
                            e is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
                            else -> "TASK_EXECUTION_ERROR"
                        }

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
                        logger.warn { "Non-communication error detected, not incrementing failure counter" }
                    }

                    val backoffDelay =
                        if (isCommunicationError) {
                            minOf(30_000L * consecutiveFailures, maxRetryDelay)
                        } else {
                            0L
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
                                correlationId = task.id.toString(),
                            )
                            logger.info { "Published LLM error to notifications for task ${task.id}" }
                            pendingTaskService.deleteTask(task.id)
                        } else {
                            userTaskService.failAndEscalateToUserTask(task, reason = errorType, error = e)
                            val possibleState =
                                setOf(
                                    PendingTaskStateEnum.QUALIFYING,
                                    PendingTaskStateEnum.DISPATCHED_GPU,
                                    PendingTaskStateEnum.READY_FOR_GPU,
                                )
                            possibleState.forEach { state ->
                                pendingTaskService.updateState(
                                    task.id,
                                    state,
                                    PendingTaskStateEnum.ERROR,
                                )
                            }
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
                                taskType = PendingTaskTypeEnum.SCHEDULED_PROCESSING,
                                content = task.content,
                                clientId = task.clientId,
                                projectId = task.projectId,
                                correlationId = task.correlationId,
                                state = PendingTaskStateEnum.READY_FOR_GPU,
                                sourceUrn = task.sourceUrn,
                            )

                            if (task.cronExpression != null) {
                                try {
                                    val cron = CronExpression.parse(task.cronExpression)
                                    val nextOccurrence =
                                        cron.next(ZonedDateTime.ofInstant(task.scheduledAt, ZoneId.systemDefault()))

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
