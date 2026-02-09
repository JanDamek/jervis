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
 * THREE INDEPENDENT LOOPS:
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
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    private var orchestratorResultJob: Job? = null
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

        orchestratorResultJob =
            scope.launch {
                try {
                    logger.info { "Orchestrator result loop STARTED (polls Python orchestrator)" }
                    runOrchestratorResultLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Orchestrator result loop FAILED to start!" }
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
                // 1. Check for FOREGROUND tasks first (chat messages have priority)
                var task = taskService.getNextForegroundTask()

                // 2. If no FOREGROUND tasks, check for BACKGROUND tasks
                if (task == null) {
                    task = taskService.getNextBackgroundTask()
                }

                if (task != null) {
                    logger.info {
                        "GPU_TASK_PICKUP: id=${task.id} correlationId=${task.correlationId} " +
                            "type=${task.type} state=${task.state} processingMode=${task.processingMode} " +
                            "queuePosition=${task.queuePosition}"
                    }

                    executeTask(task)

                    logger.info {
                        "GPU_TASK_FINISHED: id=${task.id} correlationId=${task.correlationId} " +
                            "processingMode=${task.processingMode}"
                    }
                } else {
                    // No tasks in either queue, wait before checking again
                    delay(backgroundProperties.waitOnError)
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
                    val freshTask = taskRepository.findById(task.id)
                    if (freshTask?.state == TaskStateEnum.PYTHON_ORCHESTRATING) {
                        logger.info { "PYTHON_DISPATCHED: taskId=${task.id} → releasing execution slot, result via orchestratorResultLoop" }
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
                    "runningProjectName" to "None",
                    "runningTaskPreview" to "",
                    "runningTaskType" to "",
                    "queueSize" to remainingQueueSize.toString(),
                ) + pendingItems,
            ),
        )
    }

    /**
     * Orchestrator result loop – polls Python orchestrator for tasks in PYTHON_ORCHESTRATING state.
     *
     * Runs independently from execution loop. Checks every 5s for tasks dispatched
     * to the Python orchestrator and handles their results:
     * - "running" → skip (still working)
     * - "interrupted" → escalate to USER_TASK (approval required)
     * - "done" → emit result, update state to DISPATCHED_GPU
     * - "error" → mark ERROR, escalate
     * - Python unreachable → skip (retry on next cycle)
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

                delay(5_000)  // Poll every 5 seconds
            } catch (e: CancellationException) {
                logger.info { "Orchestrator result loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in orchestrator result loop" }
                delay(10_000)
            }
        }
    }

    /**
     * Check the status of a single Python-orchestrated task.
     */
    private suspend fun checkOrchestratorTaskStatus(task: TaskDocument) {
        val threadId = task.orchestratorThreadId ?: return

        val status = try {
            pythonOrchestratorClient.getStatus(threadId)
        } catch (e: Exception) {
            logger.debug { "Python orchestrator unreachable for thread $threadId: ${e.message}" }
            return  // Skip, retry on next cycle
        }

        val state = status["status"] ?: "unknown"

        when (state) {
            "running" -> {
                // Still working – skip
            }
            "interrupted" -> {
                // Interrupted → escalate to USER_TASK via UserTaskService
                // Distinguishes between clarification (pre-planning questions) and approval (commit/push)
                val interruptAction = status["interrupt_action"] ?: "unknown"
                val interruptDescription = status["interrupt_description"] ?: "Schválení vyžadováno"

                // Clarification vs approval: different question format for the user
                val (pendingQuestion, questionContext, phase) = when (interruptAction) {
                    "clarify" -> Triple(
                        interruptDescription,  // Questions directly, no "Schválení:" prefix
                        "Orchestrátor potřebuje upřesnění před zahájením práce",
                        "clarification",
                    )
                    else -> Triple(
                        "Schválení: $interruptAction – $interruptDescription",
                        "Python orchestrátor potřebuje schválení pro: $interruptAction",
                        "approval",
                    )
                }

                userTaskService.failAndEscalateToUserTask(
                    task = task,
                    reason = "ORCHESTRATOR_INTERRUPT",
                    pendingQuestion = pendingQuestion,
                    questionContext = questionContext,
                    interruptAction = interruptAction,
                    isApproval = interruptAction != "clarify",
                )

                // Also emit progress to chat for FOREGROUND tasks
                if (task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
                    try {
                        agentOrchestratorRpc.emitProgress(
                            clientId = task.clientId.toString(),
                            projectId = task.projectId?.toString(),
                            message = if (phase == "clarification")
                                "Orchestrátor potřebuje upřesnění" else
                                "Orchestrátor potřebuje schválení: $interruptAction",
                            metadata = mapOf("phase" to phase, "action" to interruptAction),
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to emit interrupt notification for task ${task.id}" }
                    }
                }

                logger.info { "ORCHESTRATOR_INTERRUPTED: taskId=${task.id} action=$interruptAction phase=$phase → USER_TASK" }
            }
            "done" -> {
                // Completed → fetch full result and handle
                val result = try {
                    pythonOrchestratorClient.getStatus(threadId)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch orchestrator result for thread $threadId" }
                    return
                }

                // Emit final response for FOREGROUND tasks
                if (task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
                    val summary = result["summary"] ?: "Orchestrace dokončena"
                    try {
                        agentOrchestratorRpc.emitToChatStream(
                            clientId = task.clientId.toString(),
                            projectId = task.projectId?.toString(),
                            response = com.jervis.dto.ChatResponseDto(summary),
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to emit orchestrator result for task ${task.id}" }
                    }
                }

                // Check if new user messages arrived during orchestration (inline messages)
                val hasInlineMessages = task.orchestrationStartedAt?.let { startedAt ->
                    try {
                        chatMessageRepository.countByTaskIdAndRoleAndTimestampAfter(
                            taskId = task.id,
                            role = com.jervis.entity.MessageRole.USER,
                            timestamp = startedAt,
                        ) > 0
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to check for inline messages for task ${task.id}" }
                        false
                    }
                } ?: false

                if (hasInlineMessages && task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
                    // New messages arrived during orchestration — auto-requeue for processing
                    val requeuedTask = task.copy(
                        state = TaskStateEnum.READY_FOR_GPU,
                        orchestratorThreadId = null,
                        orchestrationStartedAt = null,
                    )
                    taskRepository.save(requeuedTask)
                    logger.info { "INLINE_REQUEUE: taskId=${task.id} - new user messages arrived during orchestration, re-queuing" }
                } else {
                    // Normal completion
                    val updatedTask = task.copy(
                        state = TaskStateEnum.DISPATCHED_GPU,
                        orchestratorThreadId = null,
                        orchestrationStartedAt = null,
                    )
                    taskRepository.save(updatedTask)

                    // Background tasks: delete after completion
                    if (task.processingMode == com.jervis.entity.ProcessingMode.BACKGROUND) {
                        taskRepository.delete(updatedTask)
                    }
                }

                logger.info { "ORCHESTRATOR_COMPLETE: taskId=${task.id} hasInlineMessages=$hasInlineMessages" }
            }
            "error" -> {
                val errorMsg = status["error"] ?: "Unknown orchestrator error"
                logger.error { "ORCHESTRATOR_ERROR: taskId=${task.id} error=$errorMsg" }

                userTaskService.failAndEscalateToUserTask(
                    task = task,
                    reason = "PYTHON_ORCHESTRATOR_ERROR",
                )
                taskService.updateState(task, TaskStateEnum.ERROR)
            }
        }
    }

    companion object {
        private val currentTaskJob = AtomicReference<Job?>(null)
    }
}
