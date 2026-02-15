package com.jervis.service.background

import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.dto.TaskStateEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.task.UserTaskService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that processes PendingTasks.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Qualifier structures data → routes to DONE or READY_FOR_GPU
 * - GPU tasks processed only during idle time (no user requests)
 * - Preemption: User requests to immediately interrupt background tasks
 *
 * FOUR INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 *    - Creates Graph nodes and RAG chunks with chunking for large documents
 *    - Routes tasks: DONE (simple) or READY_FOR_GPU (complex)
 *
 * 2. Execution loop (GPU) - processes qualified tasks during idle GPU time
 *    - Only runs when no active user requests (checked via LlmLoadMonitor)
 *    - Process READY_FOR_GPU tasks through Python orchestrator
 *    - Loads TaskMemory context from Qualifier for efficient execution
 *    - Preemption: Interrupted immediately when a user request arrives
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
@Order(10)
class BackgroundEngine(
    private val taskService: TaskService,
    private val taskQualificationService: TaskQualificationService,
    private val agentOrchestrator: AgentOrchestratorService,
    private val backgroundProperties: BackgroundProperties,
    private val userTaskService: UserTaskService,
    private val agentOrchestratorRpc: com.jervis.rpc.AgentOrchestratorRpcImpl,
    private val projectService: com.jervis.service.project.ProjectService,
    private val taskRepository: com.jervis.repository.TaskRepository,
    private val pythonOrchestratorClient: PythonOrchestratorClient,
    private val chatMessageRepository: com.jervis.repository.ChatMessageRepository,
    private val orchestratorStatusHandler: com.jervis.service.agent.coordinator.OrchestratorStatusHandler,
    private val orchestratorHeartbeatTracker: com.jervis.service.agent.coordinator.OrchestratorHeartbeatTracker,
    private val taskNotifier: TaskNotifier,
    private val gitRepositoryService: com.jervis.service.indexing.git.GitRepositoryService,
    private val projectRepository: com.jervis.repository.ProjectRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    private var orchestratorResultJob: Job? = null
    private var workspaceRetryJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L
    private val schedulerAdvance = Duration.ofMinutes(10)

    // Atomic flag to ensure @PostConstruct is called only once
    private val isInitialized =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @PostConstruct
    fun start() {
        if (!isInitialized.compareAndSet(false, true)) {
            logger.error {
                "BackgroundEngine.start() called multiple times! This should never happen. " +
                    "Ignoring duplicate initialization to prevent multiple qualifier agent instances."
            }
            return
        }

        logger.info { "BackgroundEngine starting - initializing four independent loops..." }

        // Recover tasks stuck in transient states from previous pod crash
        scope.launch {
            try {
                val staleThreshold = java.time.Instant.now().minus(Duration.ofMinutes(10))
                val resetCount = taskService.resetStaleTasks(staleThreshold)
                if (resetCount > 0) {
                    logger.info { "Recovered $resetCount stale tasks after pod restart" }
                } else {
                    logger.info { "No stale tasks found after pod restart" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to recover stale tasks on startup" }
            }
        }

        // Initialize workspace for all projects with git resources
        scope.launch {
            try {
                logger.info { "Starting workspace initialization check for all projects..." }
                initializeAllProjectWorkspaces()
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize project workspaces on startup" }
            }
        }

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

        orchestratorResultJob =
            scope.launch {
                try {
                    logger.info { "Orchestrator result loop STARTED (polls Python orchestrator)" }
                    runOrchestratorResultLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Orchestrator result loop FAILED to start!" }
                }
            }

        schedulerJob =
            scope.launch {
                try {
                    logger.info { "Scheduler loop STARTED (dispatches scheduled tasks ${schedulerAdvance.toMinutes()}min in advance)" }
                    runSchedulerLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Scheduler loop FAILED to start!" }
                }
            }

        workspaceRetryJob =
            scope.launch {
                try {
                    logger.info { "Workspace retry loop STARTED (checks every 60s for CLONE_FAILED projects)" }
                    runWorkspaceRetryLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Workspace retry loop FAILED to start!" }
                }
            }

        logger.info { "BackgroundEngine initialization complete - all loops launched with singleton guarantee" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Background engine stopping..." }
        currentTaskJob.getAndSet(null)?.cancel(CancellationException("Application shutdown"))
        qualificationJob?.cancel()
        executionJob?.cancel()
        schedulerJob?.cancel()
        orchestratorResultJob?.cancel()
        workspaceRetryJob?.cancel()
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                withTimeout(3000) {
                    qualificationJob?.join()
                    executionJob?.join()
                    schedulerJob?.join()
                    orchestratorResultJob?.join()
                }
            }
        } catch (_: Exception) {
            logger.debug { "Background engine shutdown timeout" }
        }
    }

    /**
     * Qualification loop - continuously operates on the CPU, unaffected by the GPU's status.
     It handles the entire sequence of tasks requiring qualification by employing a concurrency limit.
     The process is straightforward: load the task sequence, process all tasks with semaphore, pause for 30 seconds if no tasks are pending, and then restart.

     SINGLETON GUARANTEE:
     - This function is invoked EXCLUSIVELY ONCE from the @PostConstruct start() method.
     - BackgroundEngine is a singleton designated as a @Service by Spring.
     - The isInitialized flag ensures that start() is not executed multiple times (as a safeguard).
     - processAllQualifications() incorporates its own singleton lock (isQualificationRunning).
     - Every task uses the setToQualifying() atomic operation.

     OUTCOME: A maximum of ONE qualifier agent operates per application instance, assured at three distinct levels:
     1. Spring's @Service singleton management.
     2. The BackgroundEngine.isInitialized flag.
     3. The TaskQualificationService.isQualificationRunning flag.
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

    /**
     * Execution loop - processes tasks during idle GPU time.
     *
     * Processing order:
     * 1. FOREGROUND tasks (chat) - ordered by queuePosition (user can reorder in UI)
     * 2. BACKGROUND tasks (autonomous) - ordered by createdAt (oldest first, FIFO)
     *
     * FOREGROUND tasks take precedence over BACKGROUND tasks.
     */
    private suspend fun runExecutionLoop() {
        while (scope.isActive) {
            try {
                // 0. Check if Python orchestrator is already processing a task
                val orchestratingCount = taskRepository.countByState(TaskStateEnum.PYTHON_ORCHESTRATING)

                if (orchestratingCount > 0) {
                    // PREEMPTIVE PRIORITY: If FOREGROUND task exists while BACKGROUND is running → interrupt BACKGROUND
                    val waitingForegroundTask = taskService.getNextForegroundTask()

                    if (waitingForegroundTask != null) {
                        // Find currently running task from DB (currentRunningTask is cleared when dispatched to Python)
                        val runningTasks =
                            taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.PYTHON_ORCHESTRATING)
                                .toList()
                        val runningTask = runningTasks.firstOrNull()

                        if (runningTask != null && runningTask.processingMode == com.jervis.entity.ProcessingMode.BACKGROUND) {
                            logger.warn {
                                "PREEMPT: FOREGROUND task ${waitingForegroundTask.id} (queue=${waitingForegroundTask.queuePosition}) " +
                                    "arrived while BACKGROUND task ${runningTask.id} is running → interrupting BACKGROUND"
                            }

                            // Interrupt the BACKGROUND task - orchestrator will save checkpoint and return
                            val interrupted = interruptBackgroundTask(runningTask)

                            if (interrupted) {
                                logger.info { "PREEMPT_SUCCESS: BACKGROUND task ${runningTask.id} interrupted, will resume later" }
                                // Continue to next iteration - FOREGROUND task will be picked up
                                continue
                            } else {
                                logger.warn { "PREEMPT_FAILED: Could not interrupt BACKGROUND task ${runningTask.id}, will wait" }
                                delay(1_000)
                                continue
                            }
                        } else {
                            // FOREGROUND is already running, or no running task found - wait
                            delay(5_000)
                            continue
                        }
                    } else {
                        // No FOREGROUND tasks waiting - just wait for current task to finish
                        delay(5_000)
                        continue
                    }
                }

                // 1. Check for FOREGROUND tasks first (chat messages have priority)
                var task = taskService.getNextForegroundTask()

                // 2. If no FOREGROUND tasks, check for BACKGROUND tasks
                if (task == null) {
                    task = taskService.getNextBackgroundTask()
                }

                if (task != null) {
                    // Atomically claim the task — prevents duplicate execution if 2 instances overlap
                    val claimed = taskService.claimForExecution(task)
                    if (claimed == null) {
                        logger.info { "GPU_TASK_SKIP: id=${task.id} - already claimed by another instance" }
                    } else {
                        logger.info {
                            "GPU_TASK_PICKUP: id=${claimed.id} correlationId=${claimed.correlationId} " +
                                "type=${claimed.type} state=${claimed.state} processingMode=${claimed.processingMode} " +
                                "queuePosition=${claimed.queuePosition}"
                        }

                        executeTask(claimed)

                        logger.info {
                            "GPU_TASK_FINISHED: id=${claimed.id} correlationId=${claimed.correlationId} " +
                                "processingMode=${claimed.processingMode}"
                        }
                    }
                } else {
                    // No tasks — wait for notification or poll every 5s as safety net
                    taskNotifier.awaitTask(5000L)
                }
            } catch (e: CancellationException) {
                logger.info { "Execution loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in execution loop - will retry in ${backgroundProperties.waitOnError} (configured)" }
                delay(backgroundProperties.waitOnError)
            }
        }
    }

    /**
     * Interrupt a running BACKGROUND task to allow FOREGROUND task to run immediately.
     *
     * Sends interrupt request to Python orchestrator, which saves checkpoint to MongoDB
     * and returns. The BACKGROUND task can be resumed later from the checkpoint.
     *
     * @param task The BACKGROUND task currently running
     * @return true if interrupt was successful, false otherwise
     */
    private suspend fun interruptBackgroundTask(task: TaskDocument): Boolean {
        if (task.orchestratorThreadId == null) {
            logger.warn { "PREEMPT_SKIP: Task ${task.id} has no orchestratorThreadId, cannot interrupt" }
            return false
        }

        try {
            logger.info { "PREEMPT_INTERRUPT: Sending interrupt to thread ${task.orchestratorThreadId}" }

            // Send interrupt request to Python orchestrator
            val success = pythonOrchestratorClient.interrupt(task.orchestratorThreadId)

            if (success) {
                // Reset task state to READY_FOR_GPU so it can be resumed later
                // The checkpoint is already saved in MongoDB by the orchestrator
                taskRepository.save(
                    task.copy(
                        state = TaskStateEnum.READY_FOR_GPU,
                        // Keep orchestratorThreadId so it can resume from checkpoint
                    ),
                )

                // Clear running task tracking
                taskService.setRunningTask(null)

                logger.info { "PREEMPT_COMPLETE: Task ${task.id} interrupted and reset to READY_FOR_GPU" }
                return true
            } else {
                logger.warn { "PREEMPT_FAILED: Orchestrator returned false for interrupt" }
                return false
            }
        } catch (e: Exception) {
            logger.error(e) { "PREEMPT_ERROR: Failed to interrupt task ${task.id}" }
            return false
        }
    }

    private suspend fun executeTask(task: TaskDocument) {
        val taskJob =
            scope.launch {
                logger.info { "GPU_EXECUTION_START: id=${task.id} correlationId=${task.correlationId} type=${task.type}" }

                // Mark task as running and emit queue status
                taskService.setRunningTask(task)
                emitQueueStatus(task)

                try {
                    // Create progress callback that emits to chat stream
                    val onProgress: suspend (String, Map<String, String>) -> Unit = { message, metadata ->
                        try {
                            agentOrchestratorRpc.emitProgress(
                                clientId = task.clientId.toString(),
                                projectId = task.projectId?.toString(),
                                message = message,
                                metadata = metadata,
                            )
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to emit progress for task ${task.id}" }
                        }
                    }

                    val finalResponse = agentOrchestrator.run(task, task.content, onProgress)

                    // Check if task was dispatched to Python orchestrator (fire-and-forget)
                    val freshTask = taskRepository.getById(task.id)
                    if (freshTask?.state == TaskStateEnum.PYTHON_ORCHESTRATING) {
                        // Reset dispatch retry count on successful dispatch
                        if (freshTask.dispatchRetryCount > 0) {
                            taskRepository.save(freshTask.copy(dispatchRetryCount = 0, nextDispatchRetryAt = null))
                        }
                        logger.info { "PYTHON_DISPATCHED: taskId=${task.id} → releasing execution slot, result via orchestratorResultLoop" }
                        // Keep currentRunningTask set — task is still running on Python orchestrator.
                        // OrchestratorStatusHandler will clear it when orchestration finishes.
                        // Re-emit queue status so UI reflects the task is still processing.
                        emitQueueStatus(freshTask ?: task)
                        return@launch
                    }

                    // Dispatch to orchestrator failed — task still DISPATCHED_GPU, reset for retry
                    // This is a TEMPORARY condition (orchestrator busy/down) — silent retry with exponential backoff
                    if (freshTask?.state == TaskStateEnum.DISPATCHED_GPU && finalResponse.message.isNotBlank()) {
                        val newRetryCount = freshTask.dispatchRetryCount + 1
                        // Exponential backoff: 5s, 15s, 30s, 60s, 5min (cap)
                        val backoffMs = minOf(
                            5_000L * (1L shl minOf(newRetryCount - 1, 30)),
                            300_000L,
                        )
                        val nextRetryAt = java.time.Instant.now().plusMillis(backoffMs)
                        logger.info { "PYTHON_DISPATCH_BUSY: taskId=${task.id} — retry #$newRetryCount, backoff ${backoffMs}ms, next at $nextRetryAt" }
                        taskRepository.save(
                            freshTask.copy(
                                state = TaskStateEnum.READY_FOR_GPU,
                                dispatchRetryCount = newRetryCount,
                                nextDispatchRetryAt = nextRetryAt,
                            ),
                        )
                        taskService.setRunningTask(null)
                        emitIdleQueueStatus(task.clientId)
                        return@launch
                    }

                    logger.info { "GPU_EXECUTION_SUCCESS: id=${task.id} correlationId=${task.correlationId}" }

                    // Emit final response to chat stream for FOREGROUND tasks
                    if (task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
                        try {
                            agentOrchestratorRpc.emitToChatStream(
                                clientId = task.clientId.toString(),
                                projectId = task.projectId?.toString(),
                                response = finalResponse,
                            )
                            logger.info { "FINAL_RESPONSE_EMITTED | taskId=${task.id} | messageLength=${finalResponse.message.length}" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to emit final response for task ${task.id}" }
                        }
                    }

                    // Handle task cleanup based on processingMode
                    when (task.processingMode) {
                        com.jervis.entity.ProcessingMode.FOREGROUND -> {
                            // FOREGROUND tasks (chat) are NEVER deleted - they serve as conversation context
                            // Agent checkpoint is preserved in agentCheckpointJson for future continuations
                            // BUT: Change state to DISPATCHED_GPU so task is not picked up again
                            taskService.updateState(task, TaskStateEnum.DISPATCHED_GPU)
                            logger.info {
                                "FOREGROUND_TASK_COMPLETED | taskId=${task.id} | state=DISPATCHED_GPU | keeping for chat continuation"
                            }
                        }

                        com.jervis.entity.ProcessingMode.BACKGROUND -> {
                            // BACKGROUND tasks are deleted after completion (unless USER_TASK)
                            if (task.state != com.jervis.dto.TaskStateEnum.USER_TASK) {
                                taskRepository.delete(task)
                                logger.info { "BACKGROUND_TASK_DELETED | taskId=${task.id} | task completed and cleaned up" }
                            } else {
                                logger.info { "BACKGROUND_TASK_USER_TASK | taskId=${task.id} | keeping until user responds" }
                            }
                        }
                    }

                    // Clear running task and emit updated queue status
                    val clientIdForStatus = task.clientId
                    taskService.setRunningTask(null)

                    // Emit queue status with actual remaining queue count
                    try {
                        emitIdleQueueStatus(clientIdForStatus)
                        logger.info { "QUEUE_STATUS_CLEARED | clientId=$clientIdForStatus" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to emit queue status after task completion" }
                    }

                    consecutiveFailures = 0
                } catch (_: CancellationException) {
                    logger.info { "GPU_EXECUTION_INTERRUPTED: id=${task.id} correlationId=${task.correlationId}" }

                    val clientIdForStatus = task.clientId
                    taskService.setRunningTask(null)

                    // Emit queue status with actual remaining queue count
                    try {
                        emitIdleQueueStatus(clientIdForStatus)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to emit queue status after cancellation" }
                    }
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

                    val clientIdForStatus = task.clientId
                    taskService.setRunningTask(null)

                    try {
                        if (isCommunicationError) {
                            val errorMessage =
                                "Background task failed (${task.type}): $errorType - ${e.message}"
                            logger.info { "Published LLM error to notifications for task ${task.id}" }
                            taskService.deleteTask(task)
                        } else {
                            userTaskService.failAndEscalateToUserTask(task, reason = errorType, error = e)
                            taskService.updateState(task, TaskStateEnum.ERROR)
                        }
                    } catch (esc: Exception) {
                        logger.error(esc) { "Failed to handle task error for ${task.id}" }
                        taskService.deleteTask(task)
                    }

                    // Emit updated queue status after error
                    try {
                        emitIdleQueueStatus(clientIdForStatus)
                    } catch (qe: Exception) {
                        logger.warn(qe) { "Failed to emit queue status after error" }
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
     * Emit queue status to all connected clients for this task's clientId.
     * Includes pending queue items info for UI display.
     */
    private suspend fun emitQueueStatus(runningTask: TaskDocument?) {
        try {
            if (runningTask != null) {
                // Task is running - emit status with task details
                val (_, queueSize) = taskService.getQueueStatus(runningTask.clientId, runningTask.projectId)

                // Get project name
                val projectName =
                    runningTask.projectId?.let { projectId ->
                        try {
                            projectService.getProjectById(projectId).name
                        } catch (e: Exception) {
                            null
                        }
                    } ?: "General"

                // Get task text preview (first 50 chars)
                val taskPreview =
                    runningTask.content.take(50).let {
                        if (runningTask.content.length > 50) "$it..." else it
                    }

                val taskTypeLabel =
                    when (runningTask.type) {
                        com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
                        com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
                        com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
                        com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
                        else -> runningTask.type.toString()
                    }

                // Get pending queue items for display (both FOREGROUND and BACKGROUND)
                val pendingTasks = taskService.getPendingForegroundTasks(runningTask.clientId)
                val backgroundTasks = taskService.getPendingBackgroundTasks(runningTask.clientId)
                val pendingItems = buildMap {
                    put("pendingItemCount", pendingTasks.size.toString())
                    pendingTasks.forEachIndexed { index, task ->
                        val preview = task.content.take(60).let {
                            if (task.content.length > 60) "$it..." else it
                        }
                        val pName = task.projectId?.let { pid ->
                            try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                        } ?: "General"
                        put("pendingItem_${index}_preview", preview)
                        put("pendingItem_${index}_project", pName)
                        put("pendingItem_${index}_taskId", task.id.toString())
                    }
                    put("backgroundItemCount", backgroundTasks.size.toString())
                    backgroundTasks.forEachIndexed { index, task ->
                        val preview = task.content.take(60).let {
                            if (task.content.length > 60) "$it..." else it
                        }
                        val pName = task.projectId?.let { pid ->
                            try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                        } ?: "General"
                        put("backgroundItem_${index}_preview", preview)
                        put("backgroundItem_${index}_project", pName)
                        put("backgroundItem_${index}_taskId", task.id.toString())
                    }
                }

                val response =
                    com.jervis.dto.ChatResponseDto(
                        message = "Queue status update",
                        type = com.jervis.dto.ChatResponseType.QUEUE_STATUS,
                        metadata =
                            mapOf(
                                "runningProjectId" to (runningTask.projectId?.toString() ?: "none"),
                                "runningProjectName" to projectName,
                                "runningTaskPreview" to taskPreview,
                                "runningTaskType" to taskTypeLabel,
                                "runningTaskId" to runningTask.id.toString(),
                                "queueSize" to queueSize.toString(),
                            ) + pendingItems,
                    )

                agentOrchestratorRpc.emitQueueStatus(runningTask.clientId.toString(), response)
            }
            // If runningTask is null, the initial queue status from subscribeToQueueStatus() will handle it
        } catch (e: Exception) {
            logger.error(e) { "Failed to emit queue status" }
        }
    }

    /**
     * Emit queue status when agent becomes idle (task completed/cancelled).
     * Queries actual remaining queue size and pending items.
     */
    private suspend fun emitIdleQueueStatus(clientId: com.jervis.common.types.ClientId) {
        val (_, remainingQueueSize) = taskService.getQueueStatus(clientId, null)
        val pendingTasks = taskService.getPendingForegroundTasks(clientId)
        val backgroundTasks = taskService.getPendingBackgroundTasks(clientId)
        val pendingItems = buildMap {
            put("pendingItemCount", pendingTasks.size.toString())
            pendingTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("pendingItem_${index}_preview", preview)
                put("pendingItem_${index}_project", pName)
                put("pendingItem_${index}_taskId", task.id.toString())
            }
            put("backgroundItemCount", backgroundTasks.size.toString())
            backgroundTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("backgroundItem_${index}_preview", preview)
                put("backgroundItem_${index}_project", pName)
                put("backgroundItem_${index}_taskId", task.id.toString())
            }
        }

        agentOrchestratorRpc.emitQueueStatus(
            clientId.toString(),
            com.jervis.dto.ChatResponseDto(
                message = if (remainingQueueSize > 0) "Queue: $remainingQueueSize" else "Queue is empty",
                type = com.jervis.dto.ChatResponseType.QUEUE_STATUS,
                metadata = mapOf(
                    "runningProjectId" to "none",
                    "runningProjectName" to "",
                    "runningTaskPreview" to "",
                    "runningTaskType" to "",
                    "runningTaskId" to "",
                    "queueSize" to remainingQueueSize.toString(),
                ) + pendingItems,
            ),
        )
    }

    /**
     * Scheduler loop – dispatches scheduled tasks when their scheduledAt time approaches.
     *
     * Checks every 60s for SCHEDULED_TASK documents in state NEW where
     * scheduledAt <= now + schedulerAdvance (10 min).
     *
     * For one-shot tasks: transitions to READY_FOR_QUALIFICATION (enters normal pipeline).
     * For recurring tasks (cronExpression): creates a copy for execution and updates
     * the original with the next scheduled time.
     */
    private suspend fun runSchedulerLoop() {
        delay(backgroundProperties.waitOnStartup)

        while (scope.isActive) {
            try {
                val dispatchThreshold = java.time.Instant.now().plus(schedulerAdvance)

                val dueTasks = taskRepository.findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc(
                    scheduledAt = dispatchThreshold,
                    type = com.jervis.dto.TaskTypeEnum.SCHEDULED_TASK,
                    state = TaskStateEnum.NEW,
                )

                var dispatched = 0
                dueTasks.collect { task ->
                    try {
                        dispatchScheduledTask(task)
                        dispatched++
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to dispatch scheduled task ${task.id} (${task.taskName})" }
                    }
                }

                if (dispatched > 0) {
                    logger.info { "SCHEDULER_LOOP: dispatched $dispatched scheduled task(s)" }
                }

                delay(60_000) // Check every 60s
            } catch (e: CancellationException) {
                logger.info { "Scheduler loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in scheduler loop" }
                delay(60_000)
            }
        }
    }

    /**
     * Dispatch a single scheduled task into the processing pipeline.
     *
     * One-shot (no cron): Transition task NEW → READY_FOR_QUALIFICATION, clear scheduledAt.
     * Recurring (cron): Keep original task with next scheduledAt, create a new task for execution.
     */
    private suspend fun dispatchScheduledTask(task: TaskDocument) {
        val cron = task.cronExpression

        if (cron.isNullOrBlank()) {
            // One-shot: transition directly into the pipeline
            val dispatched = task.copy(
                state = TaskStateEnum.READY_FOR_QUALIFICATION,
                scheduledAt = null, // No longer scheduled
            )
            taskRepository.save(dispatched)
            taskNotifier.notifyNewTask()
            logger.info {
                "SCHEDULED_DISPATCH: one-shot task ${task.id} '${task.taskName}' → READY_FOR_QUALIFICATION"
            }
        } else {
            // Recurring: calculate next run time and create execution copy
            val nextRun = try {
                val cronExpr = org.springframework.scheduling.support.CronExpression.parse(cron)
                val next = cronExpr.next(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.now(),
                    java.time.ZoneId.systemDefault(),
                ))
                next?.atZone(java.time.ZoneId.systemDefault())?.toInstant()
            } catch (e: Exception) {
                logger.warn { "Invalid cron expression '$cron' for task ${task.id}, treating as one-shot" }
                null
            }

            // Create execution copy (enters pipeline)
            val executionTask = TaskDocument(
                type = task.type,
                taskName = task.taskName,
                content = task.content,
                clientId = task.clientId,
                projectId = task.projectId,
                state = TaskStateEnum.READY_FOR_QUALIFICATION,
                correlationId = "${task.correlationId}:${java.time.Instant.now().epochSecond}",
                sourceUrn = task.sourceUrn,
            )
            taskRepository.save(executionTask)
            taskNotifier.notifyNewTask()

            if (nextRun != null) {
                // Update original with next scheduled time (stays in NEW)
                taskRepository.save(task.copy(scheduledAt = nextRun))
                logger.info {
                    "SCHEDULED_DISPATCH: recurring task ${task.id} '${task.taskName}' → " +
                        "execution copy ${executionTask.id}, next run at $nextRun"
                }
            } else {
                // Cron parse failed, remove the original
                taskRepository.delete(task)
                logger.info {
                    "SCHEDULED_DISPATCH: recurring task ${task.id} '${task.taskName}' → " +
                        "execution copy ${executionTask.id}, no next run (deleted original)"
                }
            }
        }
    }

    /**
     * Orchestrator result loop – SAFETY NET polling for tasks in PYTHON_ORCHESTRATING state.
     *
     * Primary communication is push-based: Python → POST /internal/orchestrator-status
     * → OrchestratorStatusHandler handles state transitions.
     *
     * This loop is a safety net (60s interval) that catches cases where:
     * - Python push callback failed to deliver
     * - Python process restarted mid-task
     * - Network issues prevented callback delivery
     *
     * Also performs heartbeat-based stuck detection:
     * - If no heartbeat for 10 min → task considered dead → reset for retry
     */
    private suspend fun runOrchestratorResultLoop() {
        delay(backgroundProperties.waitOnStartup)

        while (scope.isActive) {
            try {
                val orchestratingTasks = taskRepository
                    .findByStateOrderByCreatedAtAsc(TaskStateEnum.PYTHON_ORCHESTRATING)

                orchestratingTasks.collect { task ->
                    try {
                        checkOrchestratorTaskStatus(task)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to check orchestrator status for task ${task.id}" }
                    }
                }

                delay(60_000)  // Safety-net polling every 60s (primary is push-based)
            } catch (e: CancellationException) {
                logger.info { "Orchestrator result loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in orchestrator result loop" }
                delay(60_000)
            }
        }
    }

    /**
     * Safety-net check for a single Python-orchestrated task.
     *
     * 1. Check heartbeat — if alive (< 10 min), skip polling entirely
     * 2. If no heartbeat for >= 10 min, poll Python for status
     * 3. If Python unreachable and no heartbeat → reset to READY_FOR_GPU for retry
     * 4. Delegates state handling to OrchestratorStatusHandler
     */
    private suspend fun checkOrchestratorTaskStatus(task: TaskDocument) {
        val threadId = task.orchestratorThreadId ?: return
        val taskIdStr = task.id.toString()

        // Heartbeat-based liveness check
        val lastHeartbeat = orchestratorHeartbeatTracker.getLastHeartbeat(taskIdStr)
        val now = java.time.Instant.now()

        if (lastHeartbeat != null) {
            val minutesSinceHeartbeat = java.time.Duration.between(lastHeartbeat, now).toMinutes()
            if (minutesSinceHeartbeat < HEARTBEAT_DEAD_THRESHOLD_MINUTES) {
                // Still alive — skip polling
                return
            }
            // Heartbeat dead — fall through to poll Python
            logger.warn { "ORCHESTRATOR_HEARTBEAT_DEAD: taskId=$taskIdStr last=${minutesSinceHeartbeat}min ago" }
        }

        // Poll Python as safety net
        val status = try {
            pythonOrchestratorClient.getStatus(threadId)
        } catch (e: Exception) {
            logger.debug { "Python orchestrator unreachable for thread $threadId: ${e.message}" }

            // If no heartbeat ever received or heartbeat dead → likely Python died
            if (lastHeartbeat == null || java.time.Duration.between(lastHeartbeat, now).toMinutes() >= HEARTBEAT_DEAD_THRESHOLD_MINUTES) {
                logger.warn { "ORCHESTRATOR_STUCK: taskId=$taskIdStr, Python unreachable, no heartbeat → resetting to READY_FOR_GPU" }
                val resetTask = task.copy(
                    state = TaskStateEnum.READY_FOR_GPU,
                    orchestratorThreadId = null,
                    orchestrationStartedAt = null,
                )
                taskRepository.save(resetTask)
                orchestratorHeartbeatTracker.clearHeartbeat(taskIdStr)
            }
            return
        }

        val state = status["status"] ?: "unknown"

        // If Python says "running" but we have no heartbeat and task has been orchestrating
        // for longer than the threshold, it's stale (checkpoint says running but nothing is active)
        if (state == "running" && lastHeartbeat == null) {
            val orchestrationAge = task.orchestrationStartedAt?.let {
                java.time.Duration.between(it, now).toMinutes()
            } ?: Long.MAX_VALUE
            if (orchestrationAge >= HEARTBEAT_DEAD_THRESHOLD_MINUTES) {
                logger.warn {
                    "ORCHESTRATOR_STALE_RUNNING: taskId=$taskIdStr — Python says 'running' but no heartbeat " +
                        "for ${orchestrationAge}min → resetting to READY_FOR_GPU"
                }
                val resetTask = task.copy(
                    state = TaskStateEnum.READY_FOR_GPU,
                    orchestratorThreadId = null,
                    orchestrationStartedAt = null,
                )
                taskRepository.save(resetTask)
                orchestratorHeartbeatTracker.clearHeartbeat(taskIdStr)
                return
            }
        }

        // Delegate to shared handler
        orchestratorStatusHandler.handleStatusChange(
            taskId = taskIdStr,
            status = state,
            summary = status["summary"],
            error = status["error"],
            interruptAction = status["interrupt_action"],
            interruptDescription = status["interrupt_description"],
            branch = status["branch"],
            artifacts = status["artifacts"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        )

        // Clear heartbeat on terminal states
        if (state in setOf("done", "error", "interrupted")) {
            orchestratorHeartbeatTracker.clearHeartbeat(taskIdStr)
        }
    }

    /**
     * Periodic loop that retries retryable CLONE_FAILED workspaces whose backoff has elapsed.
     * Only retries CLONE_FAILED_NETWORK and CLONE_FAILED_OTHER (transient errors).
     * CLONE_FAILED_AUTH and CLONE_FAILED_NOT_FOUND require user action — no auto-retry.
     * Runs every 60s, complementing the startup-only check.
     */
    private suspend fun runWorkspaceRetryLoop() {
        while (scope.isActive) {
            delay(60_000) // Check every 60 seconds
            try {
                val now = java.time.Instant.now()
                val allProjects = projectService.getAllProjects()
                val retryable = allProjects.filter { project ->
                    project.workspaceStatus != null &&
                        project.workspaceStatus.isRetryable &&
                        project.nextWorkspaceRetryAt != null &&
                        !project.nextWorkspaceRetryAt.isAfter(now)
                }
                for (project in retryable) {
                    logger.info { "WORKSPACE_RETRY: project=${project.name} status=${project.workspaceStatus} retry #${project.workspaceRetryCount + 1}" }
                    initializeProjectWorkspace(project)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in workspace retry loop" }
            }
        }
    }

    /**
     * Initialize workspace for all projects with git resources on startup.
     * For projects without workspaceStatus, trigger background clone.
     */
    private suspend fun initializeAllProjectWorkspaces() {
        val allProjects = projectService.getAllProjects()
        val gitProjects = allProjects.filter { project ->
            project.resources.any { it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY }
        }

        logger.info { "Found ${gitProjects.size} projects with git resources" }

        for (project in gitProjects) {
            when (project.workspaceStatus) {
                null -> {
                    // Legacy project - trigger initialization
                    logger.info { "Project ${project.name} has no workspaceStatus - triggering initialization" }
                    scope.launch {
                        initializeProjectWorkspace(project)
                    }
                }
                com.jervis.entity.WorkspaceStatus.CLONING -> {
                    // Already cloning - retry in case previous pod crashed
                    logger.info { "Project ${project.name} was CLONING - retrying" }
                    scope.launch {
                        initializeProjectWorkspace(project)
                    }
                }
                com.jervis.entity.WorkspaceStatus.CLONE_FAILED_AUTH,
                com.jervis.entity.WorkspaceStatus.CLONE_FAILED_NOT_FOUND -> {
                    // Non-retryable: user must fix connection/URL — skip on startup
                    logger.info { "Project ${project.name} workspace ${project.workspaceStatus} — user action required, skipping" }
                }
                com.jervis.entity.WorkspaceStatus.CLONE_FAILED_NETWORK,
                com.jervis.entity.WorkspaceStatus.CLONE_FAILED_OTHER -> {
                    // Retryable: respect backoff, periodic loop handles these
                    val now = java.time.Instant.now()
                    val nextRetry = project.nextWorkspaceRetryAt
                    if (nextRetry != null && nextRetry.isAfter(now)) {
                        logger.debug { "Project ${project.name} ${project.workspaceStatus} — backoff until $nextRetry (retry #${project.workspaceRetryCount})" }
                    } else {
                        logger.info { "Project ${project.name} ${project.workspaceStatus} — retrying on startup (attempt #${project.workspaceRetryCount + 1})" }
                        scope.launch {
                            initializeProjectWorkspace(project)
                        }
                    }
                }
                com.jervis.entity.WorkspaceStatus.READY -> {
                    logger.debug { "Project ${project.name} workspace already READY" }
                }
                com.jervis.entity.WorkspaceStatus.NOT_NEEDED -> {
                    logger.debug { "Project ${project.name} workspace NOT_NEEDED" }
                }
            }
        }
    }

    /**
     * Initialize workspace for a single project - clone all git repositories.
     * Sets workspaceStatus to CLONING, then READY or CLONE_FAILED_*.
     *
     * Called from:
     * - Background startup (for existing projects)
     * - ProjectService (when creating/updating projects with git resources)
     */
    suspend fun initializeProjectWorkspace(project: com.jervis.entity.ProjectDocument) {
        val gitResources = project.resources.filter {
            it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
        }

        if (gitResources.isEmpty()) {
            // No git resources - mark as NOT_NEEDED
            val updated = project.copy(
                workspaceStatus = com.jervis.entity.WorkspaceStatus.NOT_NEEDED,
                lastWorkspaceCheck = java.time.Instant.now(),
            )
            projectRepository.save(updated)
            return
        }

        logger.info { "Initializing workspace for project ${project.name} (${gitResources.size} git resources)" }

        // Mark as CLONING
        val cloning = project.copy(
            workspaceStatus = com.jervis.entity.WorkspaceStatus.CLONING,
            lastWorkspaceCheck = java.time.Instant.now(),
        )
        projectRepository.save(cloning)

        // Clone all git resources — classify failures by exception type
        var failureStatus: com.jervis.entity.WorkspaceStatus? = null
        var errorMessage: String? = null
        for (resource in gitResources) {
            try {
                logger.info { "WORKSPACE_INIT_START: project=${project.name} projectId=${project.id} resource=${resource.resourceIdentifier} connectionId=${resource.connectionId}" }
                val workspacePath = gitRepositoryService.ensureAgentWorkspaceReady(project, resource)
                logger.info { "WORKSPACE_INIT_SUCCESS: project=${project.name} resource=${resource.resourceIdentifier} path=$workspacePath" }
            } catch (e: com.jervis.service.indexing.git.GitAuthenticationException) {
                logger.error { "WORKSPACE_INIT_AUTH_FAILED: project=${project.name} resource=${resource.resourceIdentifier} error=${e.message}" }
                failureStatus = com.jervis.entity.WorkspaceStatus.CLONE_FAILED_AUTH
                errorMessage = e.message
                break
            } catch (e: com.jervis.service.indexing.git.GitRepositoryNotFoundException) {
                logger.error { "WORKSPACE_INIT_NOT_FOUND: project=${project.name} resource=${resource.resourceIdentifier} error=${e.message}" }
                failureStatus = com.jervis.entity.WorkspaceStatus.CLONE_FAILED_NOT_FOUND
                errorMessage = e.message
                break
            } catch (e: com.jervis.service.indexing.git.GitNetworkException) {
                logger.error { "WORKSPACE_INIT_NETWORK: project=${project.name} resource=${resource.resourceIdentifier} error=${e.message}" }
                failureStatus = com.jervis.entity.WorkspaceStatus.CLONE_FAILED_NETWORK
                errorMessage = e.message
                break
            } catch (e: Exception) {
                logger.error(e) { "WORKSPACE_INIT_ERROR: project=${project.name} resource=${resource.resourceIdentifier} error=${e.javaClass.simpleName}: ${e.message}" }
                failureStatus = com.jervis.entity.WorkspaceStatus.CLONE_FAILED_OTHER
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
                break
            }
        }

        val now = java.time.Instant.now()

        if (failureStatus == null) {
            // All resources cloned successfully
            val updated = project.copy(
                workspaceStatus = com.jervis.entity.WorkspaceStatus.READY,
                lastWorkspaceCheck = now,
                workspaceRetryCount = 0,
                nextWorkspaceRetryAt = null,
                lastWorkspaceError = null,
            )
            projectRepository.save(updated)
            logger.info { "Project ${project.name} workspace initialization complete: READY" }
        } else {
            val newRetryCount = project.workspaceRetryCount + 1

            // Non-retryable failures: no auto-retry (user must fix connection/URL)
            val nextRetryAt = if (failureStatus.isRetryable) {
                // Exponential backoff: 1min, 2min, 4min, 8min, 16min, 32min, 60min (cap)
                val backoffMs = minOf(
                    60_000L * (1L shl minOf(newRetryCount - 1, 30)),
                    3_600_000L, // max 1 hour
                )
                now.plusMillis(backoffMs)
            } else {
                null // Non-retryable — no next retry
            }

            val updated = project.copy(
                workspaceStatus = failureStatus,
                lastWorkspaceCheck = now,
                workspaceRetryCount = newRetryCount,
                nextWorkspaceRetryAt = nextRetryAt,
                lastWorkspaceError = errorMessage,
            )
            projectRepository.save(updated)
            if (nextRetryAt != null) {
                logger.info { "Project ${project.name} workspace $failureStatus (retry #$newRetryCount, next retry at $nextRetryAt): $errorMessage" }
            } else {
                logger.warn { "Project ${project.name} workspace $failureStatus (non-retryable, user must fix): $errorMessage" }
            }
        }
    }

    /**
     * Handle workspace initialization event from ProjectService.
     * Triggered when a project is created/updated with git resources.
     */
    @org.springframework.context.event.EventListener
    fun onProjectWorkspaceInitEvent(event: com.jervis.service.project.ProjectWorkspaceInitEvent) {
        logger.info { "Received workspace init event for project ${event.project.name}" }
        scope.launch {
            try {
                initializeProjectWorkspace(event.project)
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize workspace for project ${event.project.name}" }
            }
        }
    }

    companion object {
        private val currentTaskJob = AtomicReference<Job?>(null)

        /** No heartbeat for this long = orchestrator task is dead. */
        private const val HEARTBEAT_DEAD_THRESHOLD_MINUTES = 10L
    }
}
