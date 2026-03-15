package com.jervis.service.background

import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.maintenance.IdleTaskType
import com.jervis.entity.TaskDocument
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.maintenance.IdleTaskRegistry
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
import com.jervis.common.types.TaskId
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that processes PendingTasks.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Qualifier structures data → routes to DONE or QUEUED
 * - GPU tasks processed only during idle time (no user requests)
 * - Preemption: User requests to immediately interrupt background tasks
 *
 * FOUR INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 *    - Creates Graph nodes and RAG chunks with chunking for large documents
 *    - Routes tasks: DONE (simple) or QUEUED (complex)
 *
 * 2. Execution loop (GPU) - three-tier priority: FOREGROUND > BACKGROUND > IDLE
 *    - FOREGROUND (chat) tasks always processed first
 *    - BACKGROUND (user-scheduled) tasks processed when no foreground work
 *    - IDLE (system idle work) tasks processed only when truly idle
 *    - Preemption: FOREGROUND preempts BACKGROUND and IDLE; BACKGROUND preempts IDLE
 *
 * 3. Scheduler loop - dispatches scheduled tasks 10 minutes before the scheduled time
 *
 * PREEMPTION LOGIC (three-tier):
 * - FOREGROUND preempts both BACKGROUND and IDLE
 * - BACKGROUND preempts IDLE (but never preempted by IDLE)
 * - IDLE never preempts anything — runs only when truly idle
 * - reserveGpuForChat() interrupts any BACKGROUND/IDLE task
 * - Execution loop checks preemption every iteration
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
    private val clientRepository: com.jervis.repository.ClientRepository,
    private val pythonOrchestratorClient: PythonOrchestratorClient,
    private val chatMessageRepository: com.jervis.repository.ChatMessageRepository,
    private val orchestratorStatusHandler: com.jervis.service.agent.coordinator.OrchestratorStatusHandler,
    private val taskNotifier: TaskNotifier,
    private val gitRepositoryService: com.jervis.service.indexing.git.GitRepositoryService,
    private val projectRepository: com.jervis.repository.ProjectRepository,
    private val idleTaskRegistry: IdleTaskRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    private var orchestratorResultJob: Job? = null
    private var workspaceRetryJob: Job? = null
    private var idleReviewJob: Job? = null
    private var workPlanJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L
    /** Track preemption retry counts per task to avoid infinite loops. */
    private val preemptRetries = ConcurrentHashMap<TaskId, Int>()
    private val schedulerAdvance = Duration.ofMinutes(10)
    private var lastDeadlineScan: java.time.Instant = java.time.Instant.EPOCH

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
                val resetCount = taskService.resetStaleTasks()
                if (resetCount > 0) {
                    logger.info { "Recovered $resetCount stale tasks after pod restart" }
                } else {
                    logger.info { "No stale tasks found after pod restart" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to recover stale tasks on startup" }
            }
        }

        // Retry any pending KB retag-group operations from crash
        scope.launch {
            try {
                projectService.retryPendingRetags()
            } catch (e: Exception) {
                logger.error(e) { "Failed to retry pending retag-group operations" }
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

        // Periodic cleanup: mark pipeline tasks for archived clients as DONE
        scope.launch {
            delay(backgroundProperties.waitOnStartup)
            while (isActive) {
                try {
                    taskService.markArchivedClientTasksAsDone()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to clean up archived client tasks" }
                }
                delay(300_000) // Every 5 min
            }
        }

        idleReviewJob =
            scope.launch {
                try {
                    logger.info { "Idle review loop STARTED (interval: ${backgroundProperties.idleReviewInterval.toMinutes()}min, enabled: ${backgroundProperties.idleReviewEnabled})" }
                    runIdleReviewLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Idle review loop FAILED to start!" }
                }
            }

        workPlanJob =
            scope.launch {
                try {
                    logger.info { "Work plan executor loop STARTED (interval: 15s)" }
                    runWorkPlanLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Work plan executor loop FAILED to start!" }
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
        idleReviewJob?.cancel()
        workPlanJob?.cancel()
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
     * Called by the Ollama Router when all GPUs have been idle for >= gpu_idle_notify_after_s
     * (default 5 min). Runs 3-phase idle maintenance pipeline:
     *
     * Phase 1 (CPU-only): memory map cleanup, thinking map eviction, LQM drain, affair archival.
     * Phase 2 (GPU-light): KB dedup for ONE client (NORMAL priority, auto-preempted by CRITICAL).
     * Phase 3 (deep analysis): existing IDLE task mechanism for code quality, vuln scans etc.
     *
     * Each phase checks for incoming FG/BG work before proceeding.
     */
    fun onGpuIdle() {
        if (!backgroundProperties.idleReviewEnabled) {
            logger.debug { "GPU_IDLE_NOTIFY: Idle review disabled, ignoring" }
            return
        }
        scope.launch {
            try {
                // Abort if system is not truly idle
                if (hasActiveFgBgWork()) {
                    logger.debug { "GPU_IDLE_NOTIFY: System busy, skipping" }
                    return@launch
                }

                // Phase 1: CPU-only maintenance (idempotent, <5s)
                val phase1 = pythonOrchestratorClient.runMaintenance(phase = 1)
                if (phase1 != null) {
                    val total = phase1.memRemoved + phase1.thinkingEvicted + phase1.lqmDrained + phase1.affairsArchived
                    if (total > 0) {
                        logger.info {
                            "GPU_IDLE_P1: mem=${phase1.memRemoved} thinking=${phase1.thinkingEvicted} " +
                                "lqm=${phase1.lqmDrained} affairs=${phase1.affairsArchived}"
                        }
                    }
                }

                // Abort if work arrived during Phase 1
                if (hasActiveFgBgWork()) return@launch

                // Phase 2: GPU-light KB maintenance for one client
                val nextClient = phase1?.nextClientForPhase2
                if (nextClient != null) {
                    val phase2 = pythonOrchestratorClient.runMaintenance(phase = 2, clientId = nextClient)
                    if (phase2 != null && phase2.findings.isNotEmpty()) {
                        logger.info { "GPU_IDLE_P2: client=$nextClient findings=${phase2.findings.size}" }
                    }
                }

                // Abort if work arrived during Phase 2
                if (hasActiveFgBgWork()) return@launch

                // Phase 3: deep analysis via existing IDLE task mechanism
                if (!hasExistingIdleTask()) {
                    createIdleTask()
                }
            } catch (e: Exception) {
                logger.error(e) { "GPU_IDLE_MAINTENANCE: Error in idle pipeline" }
            }
        }
    }

    /**
     * Check if there are active FOREGROUND/BACKGROUND tasks (system is not truly idle).
     */
    private suspend fun hasActiveFgBgWork(): Boolean {
        val count = taskRepository.countByProcessingModeAndState(
            com.jervis.entity.ProcessingMode.FOREGROUND, TaskStateEnum.QUEUED,
        ) + taskRepository.countByProcessingModeAndState(
            com.jervis.entity.ProcessingMode.BACKGROUND, TaskStateEnum.QUEUED,
        ) + taskRepository.countByState(TaskStateEnum.PROCESSING)
        return count > 0
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
    /**
     * Execution loop — three-tier priority: FOREGROUND > BACKGROUND > IDLE.
     *
     * Processing order:
     * 1. FOREGROUND tasks (chat) — ordered by queuePosition, highest priority
     * 2. BACKGROUND tasks (user-scheduled) — ordered by priorityScore DESC, createdAt ASC
     * 3. IDLE tasks (system idle work) — only when truly idle, lowest priority
     *
     * Preemption:
     * - FOREGROUND preempts both BACKGROUND and IDLE
     * - BACKGROUND preempts IDLE
     * - IDLE never preempts anything
     */
    private suspend fun runExecutionLoop() {
        while (scope.isActive) {
            try {
                // 0. Check if Python orchestrator is already processing a task
                val orchestratingCount = taskRepository.countByState(TaskStateEnum.PROCESSING)

                if (orchestratingCount > 0) {
                    val runningTasks =
                        taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.PROCESSING)
                            .toList()
                    val runningTask = runningTasks.firstOrNull()

                    if (runningTask != null) {
                        val runningMode = runningTask.processingMode
                        val waitingForegroundTask = taskService.getNextForegroundTask()
                        val waitingBackgroundTask = if (waitingForegroundTask == null) taskService.getNextBackgroundTask() else null

                        // Preemption rules:
                        // IDLE running + FOREGROUND waiting → preempt
                        // IDLE running + BACKGROUND waiting → preempt
                        // BACKGROUND running + FOREGROUND waiting → preempt
                        val shouldPreempt = when {
                            runningMode == com.jervis.entity.ProcessingMode.IDLE &&
                                (waitingForegroundTask != null || waitingBackgroundTask != null) -> true
                            runningMode == com.jervis.entity.ProcessingMode.BACKGROUND &&
                                waitingForegroundTask != null -> true
                            else -> false
                        }

                        val preemptingTask = waitingForegroundTask ?: waitingBackgroundTask

                        if (shouldPreempt && preemptingTask != null) {
                            val retries = preemptRetries.getOrDefault(runningTask.id, 0)

                            if (retries >= 3) {
                                // Orchestrator doesn't know this task — force-reset it in DB
                                logger.warn {
                                    "PREEMPT_FORCE_RESET: ${runningMode} task ${runningTask.id} " +
                                        "unresponsive after $retries attempts → resetting to QUEUED"
                                }
                                preemptRetries.remove(runningTask.id)
                                taskRepository.save(
                                    runningTask.copy(
                                        state = TaskStateEnum.QUEUED,
                                        orchestratorThreadId = null,
                                    ),
                                )
                                taskService.setRunningTask(null)
                                continue
                            }

                            logger.warn {
                                "PREEMPT: ${preemptingTask.processingMode} task ${preemptingTask.id} " +
                                    "arrived while ${runningMode} task ${runningTask.id} is running → interrupting (attempt ${retries + 1}/3)"
                            }

                            val interrupted = interruptLowerPriorityTask(runningTask)

                            if (interrupted) {
                                preemptRetries.remove(runningTask.id)
                                logger.info { "PREEMPT_SUCCESS: ${runningMode} task ${runningTask.id} interrupted, will resume later" }
                                continue
                            } else {
                                preemptRetries[runningTask.id] = retries + 1
                                logger.warn { "PREEMPT_FAILED: Could not interrupt ${runningMode} task ${runningTask.id}, attempt ${retries + 1}/3" }
                                delay(2_000)
                                continue
                            }
                        } else {
                            // No preemption needed — wait for current task to finish
                            delay(5_000)
                            continue
                        }
                    } else {
                        delay(5_000)
                        continue
                    }
                }

                // 1. FOREGROUND tasks (chat) — highest priority
                var task = taskService.getNextForegroundTask()

                // 2. BACKGROUND tasks (user-scheduled) — skip if GPU reserved for chat
                if (task == null && !isGpuReservedForChat()) {
                    task = taskService.getNextBackgroundTask()
                }

                // 3. IDLE tasks — only when truly idle (no FG, no BG, no GPU reserved)
                if (task == null && !isGpuReservedForChat()) {
                    task = taskService.getNextIdleTask()
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
     * Interrupt a lower-priority task to allow a higher-priority task to run.
     *
     * Sends interrupt request to Python orchestrator, which saves checkpoint to MongoDB
     * and returns. The interrupted task can be resumed later from the checkpoint.
     *
     * Used for: FOREGROUND preempting BACKGROUND/IDLE, BACKGROUND preempting IDLE.
     *
     * @param task The lower-priority task currently running
     * @return true if interrupt was successful, false otherwise
     */
    private suspend fun interruptLowerPriorityTask(task: TaskDocument): Boolean {
        if (task.orchestratorThreadId == null) {
            logger.warn { "PREEMPT_SKIP: Task ${task.id} has no orchestratorThreadId, cannot interrupt" }
            return false
        }

        try {
            logger.info { "PREEMPT_INTERRUPT: Sending interrupt to thread ${task.orchestratorThreadId}" }

            // Send interrupt request to Python orchestrator
            val success = pythonOrchestratorClient.interrupt(task.orchestratorThreadId)

            if (success) {
                // Reset task state to QUEUED so it can be resumed later
                // The checkpoint is already saved in MongoDB by the orchestrator
                taskRepository.save(
                    task.copy(
                        state = TaskStateEnum.QUEUED,
                        // Keep orchestratorThreadId so it can resume from checkpoint
                    ),
                )

                // Clear running task tracking
                taskService.setRunningTask(null)

                logger.info { "PREEMPT_COMPLETE: Task ${task.id} interrupted and reset to QUEUED" }
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
                    val onProgress: suspend (String, Map<String, String>) -> Unit = { message, metadata ->
                        logger.debug { "TASK_PROGRESS: id=${task.id} step=${metadata["step"]} message=${message.take(50)}" }
                    }

                    val finalResponse = agentOrchestrator.run(task, task.content, onProgress)

                    // Check if task was dispatched to Python orchestrator (fire-and-forget).
                    // Empty response = successfully dispatched; non-empty = error/unavailable.
                    // Check for both PROCESSING (graph agent still running) and CODING
                    // (K8s Job already dispatched — Python set state to CODING via agent-dispatched callback).
                    val freshTask = taskRepository.getById(task.id)
                    if (freshTask?.state in setOf(TaskStateEnum.PROCESSING, TaskStateEnum.CODING) && finalResponse.message.isBlank()) {
                        // Reset dispatch retry count on successful dispatch
                        if (freshTask != null && freshTask.dispatchRetryCount > 0) {
                            taskRepository.save(freshTask.copy(dispatchRetryCount = 0, nextDispatchRetryAt = null))
                        }
                        val dispatchType = if (freshTask?.state == TaskStateEnum.CODING) "CODING_AGENT" else "ORCHESTRATOR"
                        logger.info { "PYTHON_DISPATCHED: taskId=${task.id} type=$dispatchType → releasing execution slot" }
                        taskService.setRunningTask(null)
                        // Don't emit idle — orchestrator/agent is still running.
                        // OrchestratorStatusHandler (or AgentTaskWatcher for CODING) handles completion.
                        return@launch
                    }

                    // Dispatch to orchestrator failed — task still PROCESSING, reset for retry
                    // This is a TEMPORARY condition (orchestrator busy/down) — silent retry with exponential backoff
                    if (freshTask?.state == TaskStateEnum.PROCESSING && finalResponse.message.isNotBlank()) {
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
                                state = TaskStateEnum.QUEUED,
                                dispatchRetryCount = newRetryCount,
                                nextDispatchRetryAt = nextRetryAt,
                            ),
                        )
                        taskService.setRunningTask(null)
                        emitIdleQueueStatus()
                        return@launch
                    }

                    logger.info { "GPU_EXECUTION_SUCCESS: id=${task.id} correlationId=${task.correlationId}" }

                    // Handle task cleanup based on processingMode
                    when (task.processingMode) {
                        com.jervis.entity.ProcessingMode.FOREGROUND -> {
                            // FOREGROUND tasks (chat) are NEVER deleted - they serve as conversation context
                            // Agent checkpoint is preserved in agentCheckpointJson for future continuations
                            // Mark as DONE — terminal state, not picked up again
                            taskService.updateState(task, TaskStateEnum.DONE)
                            logger.info {
                                "FOREGROUND_TASK_COMPLETED | taskId=${task.id} | state=DONE | keeping for chat continuation"
                            }
                        }

                        com.jervis.entity.ProcessingMode.BACKGROUND -> {
                            // BACKGROUND tasks stay as DONE — no deletion, results must be traceable
                            taskService.updateState(task, TaskStateEnum.DONE)
                            logger.info { "BACKGROUND_TASK_DONE | taskId=${task.id} | state=DONE" }
                        }

                        com.jervis.entity.ProcessingMode.IDLE -> {
                            // IDLE tasks stay as DONE — no deletion
                            taskService.updateState(task, TaskStateEnum.DONE)
                            logger.info { "IDLE_TASK_DONE | taskId=${task.id} | state=DONE" }
                        }
                    }

                    // Clear running task and emit updated queue status
                    val clientIdForStatus = task.clientId
                    taskService.setRunningTask(null)

                    // Emit queue status with actual remaining queue count
                    try {
                        emitIdleQueueStatus()
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
                        emitIdleQueueStatus()
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
                        emitIdleQueueStatus()
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
            // Emit global queue status to ALL connected clients
            agentOrchestratorRpc.emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.error(e) { "Failed to emit queue status" }
        }
    }

    /**
     * Emit queue status when agent becomes idle (task completed/cancelled).
     * Queries actual remaining queue size and pending items.
     */
    private suspend fun emitIdleQueueStatus() {
        agentOrchestratorRpc.emitGlobalQueueStatus()
    }

    /**
     * Scheduler loop – dispatches scheduled tasks when their scheduledAt time approaches.
     *
     * Checks every 60s for SCHEDULED_TASK documents in state NEW where
     * scheduledAt <= now + schedulerAdvance (10 min).
     *
     * For one-shot tasks: transitions to INDEXING (enters normal pipeline).
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
                        val client = clientRepository.getById(task.clientId)
                        if (client?.archived == true) {
                            logger.info { "SCHEDULER_SKIP_ARCHIVED: task ${task.id} (${task.taskName}) — client ${task.clientId} archived" }
                            return@collect
                        }
                        dispatchScheduledTask(task)
                        dispatched++
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to dispatch scheduled task ${task.id} (${task.taskName})" }
                    }
                }

                if (dispatched > 0) {
                    logger.info { "SCHEDULER_LOOP: dispatched $dispatched scheduled task(s)" }
                }

                // EPIC 8: Periodic deadline scan (every 5 minutes)
                val now = java.time.Instant.now()
                if (Duration.between(lastDeadlineScan, now) >= DEADLINE_SCAN_INTERVAL) {
                    try {
                        dispatchDeadlineScan()
                        lastDeadlineScan = now
                    } catch (e: Exception) {
                        logger.error(e) { "Error in deadline scan" }
                    }
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

    // OVERDUE_ESCALATION_THRESHOLD moved to companion object at end of class

    /**
     * Dispatch a single scheduled task into the processing pipeline.
     *
     * One-shot (no cron): Transition task NEW → INDEXING, clear scheduledAt.
     * Recurring (cron): Keep original task with next scheduledAt, create a new task for execution.
     * Overdue (>24h past scheduledAt): Escalate as urgent USER_TASK.
     */
    private suspend fun dispatchScheduledTask(task: TaskDocument) {
        // Escalate severely overdue tasks (>24h) as urgent USER_TASKs
        val scheduledAt = task.scheduledAt
        if (scheduledAt != null) {
            val overdue = Duration.between(scheduledAt, java.time.Instant.now())
            if (overdue > OVERDUE_ESCALATION_THRESHOLD) {
                logger.warn {
                    "SCHEDULED_ESCALATE: task ${task.id} '${task.taskName}' is ${overdue.toHours()}h overdue → USER_TASK"
                }
                userTaskService.failAndEscalateToUserTask(
                    task,
                    reason = "Prošlý naplánovaný úkol (${overdue.toHours()} hodin po termínu)",
                )
                return
            }
        }

        val cron = task.cronExpression

        if (cron.isNullOrBlank()) {
            // One-shot: transition directly into the pipeline
            val dispatched = task.copy(
                state = TaskStateEnum.INDEXING,
                scheduledAt = null, // No longer scheduled
            )
            taskRepository.save(dispatched)
            taskNotifier.notifyNewTask()
            logger.info {
                "SCHEDULED_DISPATCH: one-shot task ${task.id} '${task.taskName}' → INDEXING"
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
                state = TaskStateEnum.INDEXING,
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
     * Orchestrator result loop – SAFETY NET for tasks in PROCESSING state.
     *
     * Primary communication is push-based: Python → POST /internal/orchestrator-status
     * → OrchestratorStatusHandler handles state transitions.
     *
     * This loop is a safety net (60s interval) that catches cases where:
     * - Python push callback failed to deliver
     * - Python process restarted mid-task (crash handler best-effort callback missed)
     * - Network issues prevented callback delivery
     *
     * Stuck detection is purely timestamp-based (no in-memory heartbeat tracker):
     * - Uses task.orchestrationStartedAt from DB (survives pod restarts)
     * - If task has been in PROCESSING longer than threshold AND
     *   Python is unreachable → reset for retry
     */
    private suspend fun runOrchestratorResultLoop() {
        delay(backgroundProperties.waitOnStartup)

        while (scope.isActive) {
            try {
                val orchestratingTasks = taskRepository
                    .findByStateOrderByCreatedAtAsc(TaskStateEnum.PROCESSING)

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
     * Timestamp-based stuck detection (no in-memory heartbeat):
     * 1. If task has been orchestrating < STUCK_THRESHOLD → skip (too early to worry)
     * 2. If task has been orchestrating >= STUCK_THRESHOLD → poll Python for status
     * 3. If Python unreachable → reset to QUEUED for retry
     * 4. If Python says "running" but task age >= STUCK_THRESHOLD → stale, reset
     * 5. Otherwise delegate terminal states to OrchestratorStatusHandler
     */
    private suspend fun checkOrchestratorTaskStatus(task: TaskDocument) {
        val taskIdStr = task.id.toString()
        val now = java.time.Instant.now()

        // No threadId = dispatch failed but task stayed PROCESSING (edge case).
        // Use createdAt as age proxy — if old enough, reset immediately.
        val threadId = task.orchestratorThreadId
        if (threadId == null) {
            val taskAge = java.time.Duration.between(task.createdAt, now).toMinutes()
            if (taskAge >= STUCK_THRESHOLD_MINUTES) {
                logger.warn { "ORPHANED_PROCESSING: taskId=$taskIdStr age=${taskAge}min, no threadId → resetting to QUEUED" }
                taskRepository.save(
                    task.copy(
                        state = TaskStateEnum.QUEUED,
                        orchestratorThreadId = null,
                        orchestrationStartedAt = null,
                    ),
                )
            }
            return
        }

        // Timestamp-based: skip if task hasn't been orchestrating long enough
        val orchestrationAge = task.orchestrationStartedAt?.let {
            java.time.Duration.between(it, now).toMinutes()
        } ?: Long.MAX_VALUE

        if (orchestrationAge < STUCK_THRESHOLD_MINUTES) {
            // Task is young — trust push-based callbacks, skip polling
            return
        }

        // Task has been orchestrating >= threshold — poll Python as safety net
        val status = try {
            pythonOrchestratorClient.getStatus(threadId)
        } catch (e: Exception) {
            logger.debug { "Python orchestrator unreachable for thread $threadId: ${e.message}" }

            // Python unreachable + task old enough → likely crashed, reset for retry
            logger.warn { "ORCHESTRATOR_STUCK: taskId=$taskIdStr age=${orchestrationAge}min, Python unreachable → resetting to QUEUED" }
            val resetTask = task.copy(
                state = TaskStateEnum.QUEUED,
                orchestratorThreadId = null,
                orchestrationStartedAt = null,
            )
            taskRepository.save(resetTask)
            return
        }

        val state = status["status"] ?: "unknown"

        // Python says "running" — task is actively executing on the orchestrator.
        // Graph analyses with many vertices can legitimately run for hours.
        // Only reset if it exceeds the (generous) STUCK_THRESHOLD (3h).
        if (state == "running") {
            if (orchestrationAge >= STUCK_THRESHOLD_MINUTES) {
                logger.warn {
                    "ORCHESTRATOR_STALE_RUNNING: taskId=$taskIdStr — Python says 'running' but " +
                        "task age is ${orchestrationAge}min (>${STUCK_THRESHOLD_MINUTES}min) → resetting to QUEUED"
                }
                val resetTask = task.copy(
                    state = TaskStateEnum.QUEUED,
                    orchestratorThreadId = null,
                    orchestrationStartedAt = null,
                )
                taskRepository.save(resetTask)
            } else {
                logger.debug { "ORCHESTRATOR_ACTIVE: taskId=$taskIdStr running for ${orchestrationAge}min — OK" }
            }
            return
        }

        // Delegate terminal states to shared handler
        orchestratorStatusHandler.handleStatusChange(
            taskId = taskIdStr,
            status = state,
            summary = status["summary"],
            error = status["error"],
            interruptAction = status["interrupt_action"],
            interruptDescription = status["interrupt_description"],
            branch = status["branch"],
            artifacts = status["artifacts"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            keepEnvironmentRunning = status["keep_environment_running"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    /**
     * Periodic loop that retries retryable CLONE_FAILED workspaces whose backoff has elapsed.
     * Only retries CLONE_FAILED_NETWORK and CLONE_FAILED_OTHER (transient errors).
     * CLONE_FAILED_AUTH and CLONE_FAILED_NOT_FOUND require user action — no auto-retry.
     * Runs every 60s, complementing the startup-only check.
     */

    /**
     * Idle review loop — when no active tasks are running,
     * creates a synthetic IDLE_REVIEW task so the orchestrator can proactively review
     * project statuses, check deadlines, and organize findings in KB.
     *
     * Runs every [BackgroundProperties.idleReviewInterval] (default 30 min).
     * Skips if:
     * - idleReviewEnabled is false
     * - There are active tasks (QUEUED, PROCESSING)
     * - An existing IDLE_REVIEW task is already pending/running
     */
    /**
     * Unified idle work loop.
     *
     * Creates at most ONE idle task at a time using ProcessingMode.IDLE.
     * The execution loop picks up IDLE tasks only when no FOREGROUND or BACKGROUND work exists.
     * IDLE tasks are automatically preempted when higher-priority work arrives.
     *
     * Flow:
     * 1. Wait for system to be idle (no FG/BG tasks)
     * 2. Check if an IDLE task already exists → skip if so
     * 3. Consult IdleTaskRegistry for the next due check (priority-ordered)
     * 4. Create ONE IDLE task → execution loop picks it up
     * 5. After completion, next iteration creates the next due check
     */
    private suspend fun runIdleReviewLoop() {
        // Wait for startup + extra buffer to let system stabilize
        delay(backgroundProperties.waitOnStartup)
        delay(60_000) // Extra 1 minute after startup

        while (scope.isActive) {
            try {
                delay(backgroundProperties.idleReviewInterval)

                if (!backgroundProperties.idleReviewEnabled) {
                    continue
                }

                // Don't create idle tasks if there's any real work pending
                val hasFgBgWork = taskRepository.countByProcessingModeAndState(
                    com.jervis.entity.ProcessingMode.FOREGROUND, TaskStateEnum.QUEUED,
                ) + taskRepository.countByProcessingModeAndState(
                    com.jervis.entity.ProcessingMode.BACKGROUND, TaskStateEnum.QUEUED,
                ) + taskRepository.countByState(TaskStateEnum.PROCESSING) +
                    taskRepository.countByState(TaskStateEnum.INDEXING)

                if (hasFgBgWork > 0) {
                    logger.debug { "IDLE_TASK: System busy ($hasFgBgWork active tasks), skipping" }
                    continue
                }

                // Only ONE idle task at a time
                if (hasExistingIdleTask()) {
                    logger.debug { "IDLE_TASK: Already exists, skipping" }
                    continue
                }

                createIdleTask()
            } catch (e: CancellationException) {
                logger.info { "Idle task loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in idle task loop" }
                delay(backgroundProperties.idleReviewInterval)
            }
        }
    }

    /**
     * Check if an IDLE task already exists in the pipeline.
     */
    private suspend fun hasExistingIdleTask(): Boolean {
        val existing = taskRepository.findFirstByTypeAndStateIn(
            type = com.jervis.dto.TaskTypeEnum.IDLE_REVIEW,
            states = listOf(
                TaskStateEnum.NEW,
                TaskStateEnum.INDEXING,
                TaskStateEnum.QUEUED,
                TaskStateEnum.PROCESSING,
            ),
        )
        return existing != null
    }

    /**
     * Create a single IDLE task for the highest-priority due idle check.
     * Uses ProcessingMode.IDLE so the execution loop treats it as lowest priority.
     *
     * Task scope is GLOBAL — the prompt includes all active clients and projects
     * so the orchestrator can work across the entire system, not just one client.
     */
    private suspend fun createIdleTask() {
        val allClients = try {
            clientRepository.findAll().toList().filter { !it.archived }
        } catch (_: Exception) {
            emptyList()
        }

        if (allClients.isEmpty()) {
            logger.debug { "IDLE_TASK: No active clients found, skipping" }
            return
        }

        val lastRunTimes = buildLastRunTimes()
        val nextTask = idleTaskRegistry.getNextIdleTask(lastRunTimes)

        if (nextTask == null) {
            logger.debug { "IDLE_TASK: All tasks ran recently, skipping" }
            return
        }

        // Build client/project overview for global scope
        val activeClientIds = allClients.mapNotNull { it.id }.toSet()
        val allProjects = try {
            projectRepository.findAll().toList()
                .filter { it.clientId in activeClientIds }
        } catch (_: Exception) {
            emptyList()
        }
        val clientOverview = allClients.joinToString("\n") { client ->
            val projects = allProjects.filter { it.clientId == client.id }
            val projectNames = projects.joinToString(", ") { it.name }
            "- ${client.name} (ID: ${client.id}): projekty: $projectNames"
        }

        val taskName = idleTaskRegistry.getTaskDescription(nextTask.type)
        val prompt = buildIdleTaskPrompt(nextTask.type)
        val globalPrompt = """
            |$prompt
            |
            |## Scope: ALL clients and projects
            |
            |Proveď kontrolu napříč VŠEMI klienty a projekty (ne jen jedním).
            |Použij tool get_clients_projects() pro aktuální seznam.
            |
            |Přehled klientů:
            |$clientOverview
        """.trimMargin()

        // Use first client for DB constraint, but prompt is global
        val primaryClientId = allClients.first().id!!
        val idleTask = TaskDocument(
            type = com.jervis.dto.TaskTypeEnum.IDLE_REVIEW,
            taskName = taskName,
            content = globalPrompt,
            clientId = primaryClientId,
            state = TaskStateEnum.QUEUED,
            processingMode = com.jervis.entity.ProcessingMode.IDLE,
            sourceUrn = com.jervis.common.types.SourceUrn("system:idle-task:${nextTask.type.name.lowercase()}"),
        )
        taskRepository.save(idleTask)
        taskNotifier.notifyNewTask()

        logger.info { "IDLE_TASK: Created ${nextTask.type} task ${idleTask.id} (mode=IDLE, global scope, ${allClients.size} clients)" }
    }

    /**
     * Build lastRunTimes map by scanning recent completed IDLE_REVIEW tasks.
     * Matches task type from sourceUrn prefix (system:idle-task:<type>).
     */
    private suspend fun buildLastRunTimes(): Map<IdleTaskType, String> {
        val result = mutableMapOf<IdleTaskType, String>()
        try {
            val recentDone = taskRepository.findByTypeAndStateOrderByCreatedAtAsc(
                type = com.jervis.dto.TaskTypeEnum.IDLE_REVIEW,
                state = TaskStateEnum.DONE,
            ).toList().filter {
                it.createdAt.isAfter(java.time.Instant.now().minus(Duration.ofDays(7)))
            }

            for (task in recentDone) {
                val urn = task.sourceUrn.value
                val taskType = when {
                    urn.contains("kb_consistency_check") -> IdleTaskType.KB_CONSISTENCY_CHECK
                    urn.contains("vulnerability_scan") -> IdleTaskType.VULNERABILITY_SCAN
                    urn.contains("code_quality_scan") -> IdleTaskType.CODE_QUALITY_SCAN
                    urn.contains("documentation_freshness") -> IdleTaskType.DOCUMENTATION_FRESHNESS
                    urn.contains("learning_best_practices") -> IdleTaskType.LEARNING_BEST_PRACTICES
                    urn.contains("daily_report") -> IdleTaskType.DAILY_REPORT
                    else -> null
                } ?: continue
                // Keep the latest (list ordered by createdAt ASC, last wins)
                result[taskType] = task.createdAt.toString()
            }
        } catch (e: Exception) {
            logger.debug(e) { "IDLE_TASK: Failed to build lastRunTimes, treating all as never-run" }
        }
        return result
    }

    /**
     * EPIC 7: Build the prompt for a specific idle task type.
     * Each type gets a specialized prompt that the Python orchestrator
     * executes using its existing tool set (kb_search, code_search, etc.).
     */
    private fun buildIdleTaskPrompt(type: IdleTaskType): String = when (type) {
        IdleTaskType.KB_CONSISTENCY_CHECK -> """
            |You are performing a Knowledge Base consistency check.
            |
            |Using kb_search, check for:
            |1. **Duplicate content**: Search for similar topics and flag redundant entries.
            |2. **Contradictions**: Look for conflicting information across KB articles.
            |3. **Stale references**: Find KB entries referencing deleted or moved content.
            |4. **Orphaned nodes**: Identify KB entries with no connections to other content.
            |
            |For each finding, update the KB entry directly or store findings to KB.
            |Summarize your findings at the end.
        """.trimMargin()

        IdleTaskType.VULNERABILITY_SCAN -> """
            |You are performing a dependency vulnerability scan across all projects.
            |
            |Using code_search and kb_search:
            |1. **Find dependency manifests**: Search for package.json, build.gradle.kts,
            |   requirements.txt, pom.xml, Gemfile, go.mod files.
            |2. **Check versions**: Look for outdated or known-vulnerable dependency versions.
            |3. **Report findings**: For each vulnerability found, use store_knowledge to save
            |   a finding with severity, affected package, and recommended action.
            |
            |Focus on critical and high-severity issues. Be specific about versions.
        """.trimMargin()

        IdleTaskType.CODE_QUALITY_SCAN -> """
            |You are performing a basic code quality scan across all projects.
            |
            |Using code_search and kb_search:
            |1. **TODO/FIXME audit**: Search for TODO and FIXME comments, categorize by urgency.
            |2. **Dead code**: Look for unused imports, unreachable code, empty catch blocks.
            |3. **Complexity hotspots**: Identify overly complex functions or classes.
            |
            |For significant findings, use store_knowledge to save them. Summarize at the end.
        """.trimMargin()

        IdleTaskType.DOCUMENTATION_FRESHNESS -> """
            |You are checking documentation freshness across all projects.
            |
            |Using kb_search and code_search:
            |1. **Find documentation**: Search for README, docs/, wiki pages in KB.
            |2. **Compare with code**: Check if documented APIs/configs match current code.
            |3. **Flag stale docs**: Identify documentation that hasn't been updated
            |   but references code that has changed significantly.
            |
            |For each stale document found, use store_knowledge to save a finding with specific
            |sections that need updating. Summarize findings at the end.
        """.trimMargin()

        IdleTaskType.LEARNING_BEST_PRACTICES -> """
            |You are searching for relevant best practices for the managed projects.
            |
            |Using kb_search and code_search:
            |1. **Detect tech stack**: Identify languages, frameworks, and tools used.
            |2. **Find improvement opportunities**: Based on the tech stack, suggest
            |   best practices that are not yet followed.
            |3. **Check recent issues**: Look at recent bug fixes and suggest preventive
            |   patterns or tools.
            |
            |Create a summary with actionable recommendations. If any recommendation
            |is critical, use store_knowledge to save it for tracking.
        """.trimMargin()

        IdleTaskType.DAILY_REPORT -> """
            |You are generating today's daily activity report for Jervis.
            |
            |Gather information using kb_search:
            |1. **Completed tasks**: Search KB for tasks/issues resolved today.
            |2. **Pending approvals**: Search KB for items in Open/Review state.
            |3. **Upcoming deadlines**: Search KB for deadline mentions in the next 7 days.
            |4. **Errors**: Search KB for error reports created today.
            |5. **Stuck tasks**: Search KB for in-progress items older than 3 days without updates.
            |
            |## Verification principle
            |
            |Before creating any task or alert, always verify the FULL context:
            |search for related items, follow-ups, resolutions, and contradicting
            |information. Think critically about what you find — a single KB hit
            |is not enough to act on. Cross-reference dates, check whether the
            |situation has already been resolved, and only escalate when you are
            |confident the issue is real, current, and unhandled.
            |
            |Use store_knowledge to save the daily report summary with kind=finding.
            |
            |Keep the report concise and actionable.
        """.trimMargin()
    }

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
                com.jervis.entity.WorkspaceStatus.CLONE_FAILED_AUTH -> {
                    // Auth failure — retry on startup: token refresh may restore the connection
                    logger.info { "Project ${project.name} workspace CLONE_FAILED_AUTH — retrying (token refresh may fix it)" }
                    scope.launch {
                        initializeProjectWorkspace(project)
                    }
                }
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
                    // Verify workspace actually exists on disk (files may be gone after pod restart/PVC loss)
                    val gitResources = project.resources.filter {
                        it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                    }
                    val allExist = gitResources.all { resource ->
                        val repoDir = gitRepositoryService.getAgentRepoDir(project, resource)
                        repoDir.resolve(".git").toFile().exists()
                    }
                    if (allExist) {
                        logger.info { "Project ${project.name} workspace READY — verified ${gitResources.size} repo(s) exist on disk" }
                    } else {
                        logger.warn { "Project ${project.name} workspace READY in DB but files missing on disk — re-initializing" }
                        val resetProject = projectRepository.save(
                            project.copy(
                                workspaceStatus = null,
                                workspaceRetryCount = 0,
                                nextWorkspaceRetryAt = null,
                                lastWorkspaceError = null,
                            ),
                        )
                        scope.launch {
                            initializeProjectWorkspace(resetProject)
                        }
                    }
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

    // --- GPU reservation for foreground chat ---

    private val gpuReservedForChat = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Reserve GPU for foreground chat. Called by Python /chat endpoint
     * via /internal/foreground-start or /internal/reserve-gpu-for-chat.
     * While reserved, background and idle GPU tasks are interrupted to free the GPU
     * for chat LLM calls.
     */
    fun reserveGpuForChat() {
        val wasReserved = gpuReservedForChat.getAndSet(true)
        logger.info { "GPU_RESERVE_FOR_CHAT: reserved=true (was=$wasReserved)" }

        // Interrupt currently running BACKGROUND or IDLE task if any
        if (!wasReserved) {
            scope.launch {
                try {
                    val runningTasks = taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.PROCESSING)
                        .toList()
                    val lowerPriorityTask = runningTasks.firstOrNull {
                        it.processingMode == com.jervis.entity.ProcessingMode.BACKGROUND ||
                            it.processingMode == com.jervis.entity.ProcessingMode.IDLE
                    }
                    if (lowerPriorityTask != null) {
                        logger.info { "GPU_CHAT_PREEMPT: Interrupting ${lowerPriorityTask.processingMode} task ${lowerPriorityTask.id}" }
                        interruptLowerPriorityTask(lowerPriorityTask)
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to preempt lower-priority task for chat" }
                }
            }
        }
    }

    /**
     * Release GPU reservation after foreground chat ends. Called by Python /chat endpoint
     * via /internal/foreground-end or /internal/release-gpu-for-chat.
     */
    fun releaseGpuForChat() {
        val wasReserved = gpuReservedForChat.getAndSet(false)
        logger.info { "GPU_RELEASE_FOR_CHAT: reserved=false (was=$wasReserved)" }
    }

    /**
     * Check if GPU is currently reserved for foreground chat.
     * Used by execution loop to skip background task dispatch.
     */
    fun isGpuReservedForChat(): Boolean = gpuReservedForChat.get()

    /**
     * Chat is running on OpenRouter (cloud), GPU is available for background tasks.
     * Don't reserve GPU — just log for observability.
     */
    fun reportChatOnCloud() {
        logger.info { "CHAT_ON_CLOUD: Chat using OpenRouter, GPU available for background" }
    }

    // Backward-compatible aliases
    @Deprecated("Use reserveGpuForChat()", ReplaceWith("reserveGpuForChat()"))
    fun registerForegroundChatStart() = reserveGpuForChat()

    @Deprecated("Use releaseGpuForChat()", ReplaceWith("releaseGpuForChat()"))
    fun registerForegroundChatEnd() = releaseGpuForChat()

    @Deprecated("Use isGpuReservedForChat()", ReplaceWith("isGpuReservedForChat()"))
    fun isForegroundChatActive(): Boolean = isGpuReservedForChat()

    /**
     * EPIC 8: Dispatch a deadline scan task to the Python orchestrator.
     * Creates a SCHEDULED_TASK that scans KB and JIRA for approaching deadlines.
     * Only creates if no pending deadline scan task exists.
     */
    private suspend fun dispatchDeadlineScan() {
        // Check if a deadline scan task is already pending (by sourceUrn, not generic type)
        val pendingScans = taskRepository.findBySourceUrnAndStateIn(
            sourceUrn = com.jervis.common.types.SourceUrn("system:deadline-scan"),
            states = listOf(
                TaskStateEnum.NEW,
                TaskStateEnum.INDEXING,
                TaskStateEnum.QUEUED,
                TaskStateEnum.PROCESSING,
            ),
        )
        if (pendingScans.isNotEmpty()) {
            // Cleanup: if multiple scans queued (from accumulation bug), keep only the newest
            if (pendingScans.size > 1) {
                val sorted = pendingScans.sortedByDescending { it.createdAt }
                for (old in sorted.drop(1)) {
                    old.state = TaskStateEnum.DONE
                    taskRepository.save(old)
                }
                logger.info { "DEADLINE_SCAN: Cleaned up ${pendingScans.size - 1} stale queued scans" }
            }
            logger.debug { "DEADLINE_SCAN: Already pending, skipping" }
            return
        }

        val defaultClientId = try {
            val projects = projectRepository.findAll().toList()
            val jervisProject = projects.firstOrNull { it.name.contains("JERVIS", ignoreCase = true) }
                ?: projects.firstOrNull()
            jervisProject?.clientId
        } catch (_: Exception) {
            null
        } ?: return

        val scanTask = TaskDocument(
            type = com.jervis.dto.TaskTypeEnum.SCHEDULED_TASK,
            taskName = "Deadline Scan",
            content = DEADLINE_SCAN_PROMPT,
            clientId = defaultClientId,
            state = TaskStateEnum.QUEUED,
            processingMode = com.jervis.entity.ProcessingMode.IDLE,
            sourceUrn = com.jervis.common.types.SourceUrn("system:deadline-scan"),
        )
        taskRepository.save(scanTask)
        taskNotifier.notifyNewTask()

        logger.info { "DEADLINE_SCAN: Created deadline scan task ${scanTask.id} (mode=IDLE)" }
    }

    companion object {
        private val currentTaskJob = AtomicReference<Job?>(null)

        /** Task in PROCESSING for this long without completion = stuck (timestamp-based).
         *  Graph analyses with 50-150 vertices can take 1-3 hours — 15min was too short. */
        private const val STUCK_THRESHOLD_MINUTES = 180L

        /** Scheduled tasks overdue by more than this are escalated as urgent USER_TASKs. */
        val OVERDUE_ESCALATION_THRESHOLD: Duration = Duration.ofHours(24)

        /** EPIC 8: Interval between deadline scans. */
        val DEADLINE_SCAN_INTERVAL: Duration = Duration.ofMinutes(5)

        /** EPIC 8: Prompt for the deadline scanning orchestrator task. */
        private val DEADLINE_SCAN_PROMPT = """
            |You are performing a periodic deadline scan across all projects.
            |
            |## Step 1: Check what is already tracked or resolved
            |BEFORE creating any USER_TASK for a found item:
            |1. Use list_recent_tasks — if a USER_TASK already exists for this item (any state), SKIP.
            |2. Use kb_search with the item's identifier — if KB indicates the item is resolved, paid,
            |   closed, or otherwise handled, SKIP. User responses and resolutions are stored in KB.
            |3. Only create a USER_TASK for genuinely NEW, UNTRACKED items that need attention.
            |
            |## Step 2: Search for deadlines
            |Using kb_search, find:
            |1. Issues/tasks with due dates in the next 7 days
            |2. Documents mentioning deadlines or milestones
            |3. Overdue items that are still open
            |
            |## Step 3: Take action (only for NEW findings not already tracked)
            |- If < 1 day: Create USER_TASK with urgency=URGENT
            |- If 1-3 days: Add a comment on the JIRA issue as reminder
            |- If 3-7 days: Log the finding (no action needed)
            |- If overdue: Create USER_TASK flagging the overdue item
            |
            |## CRITICAL: USER_TASK content must include ALL details
            |When creating a USER_TASK, the taskName and content MUST include:
            |- Specific item identifier (ID, key, number)
            |- What the item is (summary, description)
            |- Which client/project it belongs to
            |- Due date and how many days overdue or remaining
            |- What specific action is needed from the user
            |The user sees ONLY the taskName and content — if details are missing, the task is USELESS.
            |
            |## Step 4: Summarize
            |Count items per urgency level. If nothing found, just report "No deadlines approaching."
        """.trimMargin()
    }

    // ── Work Plan Executor ─────────────────────────────────────────────────

    /**
     * Work Plan Executor loop — manages hierarchical task dependencies.
     *
     * Every 15 seconds:
     * 1. Find BLOCKED tasks → check if ALL blockedByTaskIds are DONE → unblock (INDEXING)
     * 2. Find BLOCKED root tasks (have children) → if all children DONE → root = DONE
     * 3. If any child ERROR → escalate root to USER_TASK
     *
     * This loop only touches BLOCKED states — never interferes with other loops.
     */
    private suspend fun runWorkPlanLoop() {
        delay(backgroundProperties.waitOnStartup)

        while (scope.isActive) {
            try {
                var unblocked = 0
                var completed = 0

                // 1. Unblock BLOCKED tasks whose dependencies are all DONE
                val blockedTasks = taskRepository.findByStateOrderByOrderInPhaseAsc(TaskStateEnum.BLOCKED)
                    .toList()

                for (task in blockedTasks) {
                    if (task.blockedByTaskIds.isEmpty()) {
                        // No dependencies — should not be BLOCKED, unblock immediately
                        taskService.updateState(task, TaskStateEnum.INDEXING)
                        unblocked++
                        continue
                    }

                    // Check if ALL blocking tasks are DONE
                    val allDone = task.blockedByTaskIds.all { depId ->
                        val depTask = taskRepository.getById(depId)
                        depTask?.state == TaskStateEnum.DONE
                    }

                    if (allDone) {
                        taskService.updateState(task, TaskStateEnum.INDEXING)
                        unblocked++
                        logger.info { "WorkPlan: Unblocked task ${task.id} (${task.taskName}), all ${task.blockedByTaskIds.size} deps done" }
                    }
                }

                // 2. Check BLOCKED root tasks (with children) — complete when all children done
                val planningTasks = taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.BLOCKED)
                    .toList()

                for (rootTask in planningTasks) {
                    val children = taskRepository.findByParentTaskId(rootTask.id).toList()
                    if (children.isEmpty()) continue // No children yet, still decomposing

                    val hasError = children.any { it.state == TaskStateEnum.ERROR }
                    if (hasError) {
                        // Escalate to USER_TASK so user sees the failure
                        val failedNames = children.filter { it.state == TaskStateEnum.ERROR }
                            .joinToString(", ") { it.taskName }
                        taskService.updateStateAndContent(
                            rootTask,
                            TaskStateEnum.USER_TASK,
                            "Work plan failed. Failed subtasks: $failedNames",
                        )
                        logger.warn { "WorkPlan: Root task ${rootTask.id} escalated to USER_TASK — child errors: $failedNames" }
                        continue
                    }

                    val notDoneCount = taskRepository.countByParentTaskIdAndStateNot(rootTask.id, TaskStateEnum.DONE)
                    if (notDoneCount == 0L) {
                        // All children DONE — complete the root task
                        val summary = children.joinToString("\n") { "- [DONE] ${it.taskName}" }
                        taskService.updateStateAndContent(
                            rootTask,
                            TaskStateEnum.DONE,
                            "Work plan completed. ${children.size} subtasks done:\n$summary",
                        )
                        completed++
                        logger.info { "WorkPlan: Root task ${rootTask.id} completed — all ${children.size} children done" }
                    }
                }

                if (unblocked > 0 || completed > 0) {
                    logger.info { "WorkPlan cycle: unblocked=$unblocked, completed=$completed" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "WorkPlan executor error" }
            }

            delay(15_000) // 15s interval
        }
    }
}
