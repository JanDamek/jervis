package com.jervis.service.background

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.ProcessingMode
import com.jervis.entity.TaskDocument
import com.jervis.configuration.properties.QualifierProperties
import com.jervis.repository.ClientRepository
import com.jervis.repository.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.text.TikaTextExtractionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val clientRepository: ClientRepository,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val qualifierProperties: QualifierProperties,
    @Lazy private val notificationRpc: NotificationRpcImpl,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    // Track current running task for queue status
    @Volatile
    private var currentRunningTask: TaskDocument? = null

    /**
     * Bulk-mark all pipeline tasks for archived clients as DONE.
     * Targets: READY_FOR_QUALIFICATION, QUALIFYING, READY_FOR_GPU.
     * Scheduled tasks (NEW) are left untouched — they resume when client is unarchived.
     * Called on startup and periodically (every 5 min) from BackgroundEngine.
     */
    suspend fun markArchivedClientTasksAsDone(): Int {
        val archivedIds = clientRepository.findByArchivedTrue().toList().map { it.id.value }
        if (archivedIds.isEmpty()) return 0

        val pipelineStates = listOf(
            TaskStateEnum.READY_FOR_QUALIFICATION.name,
            TaskStateEnum.QUALIFYING.name,
            TaskStateEnum.READY_FOR_GPU.name,
        )
        val query = Query(
            Criteria.where("clientId").`in`(archivedIds)
                .and("state").`in`(pipelineStates),
        )
        val update = Update()
            .set("state", TaskStateEnum.DONE.name)
            .set("errorMessage", "skipped: client archived")
        val result = mongoTemplate.updateMulti(query, update, TaskDocument::class.java).awaitSingle()
        if (result.modifiedCount > 0) {
            logger.info { "ARCHIVED_CLEANUP: Marked ${result.modifiedCount} task(s) as DONE for ${archivedIds.size} archived client(s)" }
        }
        return result.modifiedCount.toInt()
    }

    suspend fun createTask(
        taskType: TaskTypeEnum,
        content: String,
        clientId: ClientId,
        correlationId: String,
        sourceUrn: SourceUrn,
        projectId: ProjectId? = null,
        state: TaskStateEnum = TaskStateEnum.READY_FOR_QUALIFICATION,
        attachments: List<AttachmentMetadata> = emptyList(),
        taskName: String? = null,
    ): TaskDocument {
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val cleanContent = cleanContentWithTika(content, correlationId, taskType)

        val task =
            TaskDocument(
                type = taskType,
                taskName = taskName ?: "Unnamed Task",
                content = cleanContent,
                projectId = projectId,
                clientId = clientId,
                state = state,
                correlationId = correlationId,
                sourceUrn = sourceUrn,
                attachments = attachments,
            )

        val saved = taskRepository.save(task)

        if (saved.type != TaskTypeEnum.USER_TASK) {
            notificationRpc.emitPendingTaskCreated(saved.id.toString(), saved.type.name)
        }

        logger.info {
            "TASK_CREATED: id=${saved.id} correlationId=${saved.correlationId} type=${taskType.name} state=${task.state} clientId=$clientId projectId=${projectId?.toString() ?: "none"} contentLength=${content.length}"
        }

        return saved
    }

    suspend fun deleteTask(task: TaskDocument) {
        taskRepository.delete(task)
        logger.info { "TASK_DELETED: id=${task.id} " }
    }

    suspend fun deleteTaskById(id: TaskId) {
        taskRepository.deleteById(id)
        logger.info { "TASK_DELETED: id=$id " }
    }

    suspend fun findTasksToRun(): Flow<TaskDocument> =
        flow {
            taskRepository
                .findOneByScheduledAtLessThanAndTypeOrderByScheduledAtAsc(
                    Instant.now(),
                    TaskTypeEnum.SCHEDULED_TASK,
                )?.let {
                    emit(it)
                }
            emitAll(taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.READY_FOR_GPU))
        }

    /**
     * Get next FOREGROUND task (chat) ordered by queuePosition.
     * User can reorder tasks in UI by changing queuePosition.
     *
     * @return Next FOREGROUND task to process, or null if no tasks
     */
    suspend fun getNextForegroundTask(): TaskDocument? {
        val now = java.time.Instant.now()
        return taskRepository
            .findByProcessingModeAndStateOrderByQueuePositionAsc(
                ProcessingMode.FOREGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).firstOrNull { it.nextDispatchRetryAt == null || it.nextDispatchRetryAt <= now }
    }

    /**
     * Get next BACKGROUND task (autonomous) ordered by createdAt (oldest first).
     * Background tasks process in FIFO order (creation time).
     *
     * @return Next BACKGROUND task to process, or null if no tasks
     */
    suspend fun getNextBackgroundTask(): TaskDocument? {
        val now = java.time.Instant.now()
        return taskRepository
            .findByProcessingModeAndStateOrderByCreatedAtAsc(
                ProcessingMode.BACKGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).firstOrNull { it.nextDispatchRetryAt == null || it.nextDispatchRetryAt <= now }
    }

    /**
     * Mark task as currently running (for queue status tracking)
     */
    fun setRunningTask(task: TaskDocument?) {
        currentRunningTask = task
    }

    /**
     * Get currently running task (for progress display).
     * First checks in-memory tracking, then falls back to DB query
     * for PYTHON_ORCHESTRATING tasks (dispatched to orchestrator but still active).
     */
    suspend fun getCurrentRunningTask(): TaskDocument? =
        currentRunningTask ?: getOrchestratingTask()

    /**
     * Find any task currently being processed by the Python orchestrator.
     * Covers both FOREGROUND and BACKGROUND tasks in PYTHON_ORCHESTRATING state.
     */
    private suspend fun getOrchestratingTask(): TaskDocument? =
        taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.PYTHON_ORCHESTRATING)
            .toList()
            .firstOrNull()

    /**
     * Get queue status: currently running task and queue size.
     * Queue size counts only FOREGROUND tasks in READY_FOR_GPU state.
     * Running task is atomically claimed (DISPATCHED_GPU) so it's excluded automatically.
     */
    suspend fun getGlobalQueueStatus(): Pair<TaskDocument?, Int> {
        val rawCount =
            taskRepository.countByProcessingModeAndState(
                processingMode = ProcessingMode.FOREGROUND,
                state = TaskStateEnum.READY_FOR_GPU,
            )
        // Exclude the currently running task from the count (its DB state is still READY_FOR_GPU)
        val running = getCurrentRunningTask()
        val isRunningCounted = running != null &&
            running.processingMode == ProcessingMode.FOREGROUND &&
            running.state == TaskStateEnum.READY_FOR_GPU
        val queueSize = if (isRunningCounted) (rawCount - 1).coerceAtLeast(0) else rawCount

        logger.debug {
            "GET_GLOBAL_QUEUE_STATUS | " +
                "rawCount=$rawCount | queueSize=$queueSize | hasRunningTask=${running != null} | isRunningCounted=$isRunningCounted"
        }
        return running to queueSize.toInt()
    }

    /**
     * Get ALL pending FOREGROUND tasks for queue display (global, not per-client).
     * Returns only tasks actually waiting to be processed (READY_FOR_GPU).
     * PYTHON_ORCHESTRATING tasks are NOT in the queue — they're shown in the Agent section.
     */
    suspend fun getPendingForegroundTasks(): List<TaskDocument> {
        val running = currentRunningTask
        return taskRepository
            .findByProcessingModeAndStateOrderByQueuePositionAsc(
                ProcessingMode.FOREGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).toList()
            .filter { it.id != running?.id }
    }

    /**
     * Get ALL pending BACKGROUND tasks for queue display (global, not per-client).
     * Returns tasks waiting to be processed, excluding the currently running task.
     */
    suspend fun getPendingBackgroundTasks(): List<TaskDocument> {
        val running = currentRunningTask
        return taskRepository
            .findByProcessingModeAndStateOrderByQueuePositionAscCreatedAtAsc(
                ProcessingMode.BACKGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).toList()
            .filter { it.id != running?.id }
    }

    /**
     * Get paginated BACKGROUND tasks for infinite scroll (DB skip/limit).
     * Returns (tasks, totalCount) where totalCount is the total number of background tasks.
     */
    suspend fun getPendingBackgroundTasksPaginated(limit: Int, offset: Int): Pair<List<TaskDocument>, Long> {
        val running = currentRunningTask
        val criteria = Criteria.where("processingMode").`is`(ProcessingMode.BACKGROUND.name)
            .and("state").`is`(TaskStateEnum.READY_FOR_GPU.name)

        val totalCount = mongoTemplate.count(Query(criteria), TaskDocument::class.java).awaitSingle()

        val query = Query(criteria)
            .with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("queuePosition"),
                org.springframework.data.domain.Sort.Order.asc("createdAt"),
            ))
            .skip(offset.toLong())
            .limit(limit)

        val tasks = mongoTemplate.find(query, TaskDocument::class.java)
            .collectList()
            .awaitSingle()
            .filter { it.id != running?.id }

        return tasks to totalCount
    }

    /**
     * Reorder a task within its queue by setting a new position.
     * Recalculates positions for all tasks in the same queue to maintain contiguous ordering.
     */
    suspend fun reorderTaskInQueue(task: TaskDocument, newPosition: Int) {
        val allTasks = when (task.processingMode) {
            ProcessingMode.FOREGROUND -> getPendingForegroundTasks()
            ProcessingMode.BACKGROUND -> getPendingBackgroundTasks()
        }.sortedBy { it.queuePosition ?: Int.MAX_VALUE }.toMutableList()

        // Remove the target task from the list
        allTasks.removeAll { it.id == task.id }

        // Clamp new position to valid range (1-based)
        val clampedPos = newPosition.coerceIn(1, allTasks.size + 1)

        // Insert at the new position (convert to 0-based index)
        allTasks.add((clampedPos - 1).coerceAtLeast(0), task)

        // Recalculate all positions (1-based contiguous)
        allTasks.forEachIndexed { index, t ->
            val updatedTask = t.copy(queuePosition = index + 1)
            taskRepository.save(updatedTask)
        }

        logger.info {
            "TASK_REORDERED: id=${task.id} newPosition=$clampedPos queue=${task.processingMode} totalTasks=${allTasks.size}"
        }
    }

    /**
     * Move a task between FOREGROUND and BACKGROUND queues.
     * Assigns queuePosition at the end of the target queue.
     */
    suspend fun moveTaskToQueue(task: TaskDocument, targetMode: ProcessingMode) {
        if (task.processingMode == targetMode) {
            logger.info { "TASK_MOVE_SKIP: id=${task.id} already in $targetMode" }
            return
        }

        // Calculate next position in target queue
        val targetTasks = when (targetMode) {
            ProcessingMode.FOREGROUND -> getPendingForegroundTasks()
            ProcessingMode.BACKGROUND -> getPendingBackgroundTasks()
        }
        val maxPosition = targetTasks.maxOfOrNull { it.queuePosition ?: 0 } ?: 0

        val updatedTask = task.copy(
            processingMode = targetMode,
            queuePosition = maxPosition + 1,
        )
        taskRepository.save(updatedTask)

        logger.info {
            "TASK_MOVED: id=${task.id} from=${task.processingMode} to=$targetMode newPosition=${maxPosition + 1}"
        }
    }

    suspend fun updateState(
        task: TaskDocument,
        next: TaskStateEnum,
    ): TaskDocument {
        val fromState = task.state
        // Use targeted $set instead of full document save to preserve fields
        // modified via $push (e.g. qualificationSteps) during processing
        val query = Query(Criteria.where("_id").`is`(task.id.value))
        val update = Update().set("state", next.name)
        val options = FindAndModifyOptions.options().returnNew(true)
        val saved = mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull() ?: task.copy(state = next)
        logger.info {
            "TASK_STATE_TRANSITION: id=${task.id} correlationId=${saved.correlationId} from=$fromState to=$next type=${task.type}"
        }

        return saved
    }

    /**
     * Mark the task as ERROR with an error message, without requiring the expected state.
     * This is used for fatal errors where we need to ensure a task is marked as failed.
     */
    suspend fun markAsError(
        task: TaskDocument,
        errorMessage: String,
    ): TaskDocument {
        val previousState = task.state
        // Use targeted $set to preserve fields modified via $push (e.g. qualificationSteps)
        val query = Query(Criteria.where("_id").`is`(task.id.value))
        val update = Update()
            .set("state", TaskStateEnum.ERROR.name)
            .set("errorMessage", errorMessage)
        val options = FindAndModifyOptions.options().returnNew(true)
        val saved = mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull() ?: task.copy(state = TaskStateEnum.ERROR, errorMessage = errorMessage)
        logger.error {
            "TASK_MARKED_AS_ERROR: id=${task.id} correlationId=${saved.correlationId} previousState=$previousState error='${
                errorMessage.take(
                    200,
                )
            }'"
        }
        return saved
    }

    /**
     * Return the task from QUALIFYING to READY_FOR_QUALIFICATION for retry.
     * Uses DB-based exponential backoff: 1s, 2s, 4s, 8s, ... up to 5min, then stays at 5min forever.
     * Operational errors (timeout, connection refused) never mark as ERROR - they keep retrying.
     * The task is stored in DB with a future nextQualificationRetryAt timestamp.
     */
    suspend fun returnToQueue(task: TaskDocument) {
        // Reload from DB to get current state (caller may have stale in-memory object)
        val current = task.id?.let { taskRepository.getById(it) } ?: task
        if (current.state != TaskStateEnum.QUALIFYING) {
            logger.warn {
                "Cannot return task to queue - expected QUALIFYING but was ${current.state}: ${current.id}"
            }
            return
        }

        val newRetryCount = current.qualificationRetries + 1

        // Exponential backoff: min(initialMs * 2^(retry-1), maxMs)
        val backoffMs = minOf(
            qualifierProperties.initialBackoffMs * (1L shl minOf(newRetryCount - 1, 30)),
            qualifierProperties.maxBackoffMs,
        )
        val nextRetryAt = Instant.now().plusMillis(backoffMs)

        val updated = current.copy(
            state = TaskStateEnum.READY_FOR_QUALIFICATION,
            qualificationRetries = newRetryCount,
            nextQualificationRetryAt = nextRetryAt,
        )
        taskRepository.save(updated)

        logger.info {
            "TASK_RETURNED_TO_QUEUE: id=${current.id} correlationId=${current.correlationId} " +
                "retry=$newRetryCount backoffMs=$backoffMs nextRetryAt=$nextRetryAt"
        }
    }

    /**
     * Return all tasks eligible for qualification now:
     * - READY_FOR_QUALIFICATION where nextQualificationRetryAt is null (new tasks) OR <= now (backoff expired)
     *
     * Tasks with future nextQualificationRetryAt are hidden until their backoff window elapses.
     * Order: queuePosition ASC NULLS LAST (manually prioritized first), then createdAt ASC (FIFO).
     */
    suspend fun findTasksForQualification(): Flow<TaskDocument> =
        flow {
            // New tasks (never retried, no nextQualificationRetryAt)
            // Ordered by queuePosition (manually prioritized) then createdAt (FIFO)
            emitAll(
                taskRepository.findByStateAndNextQualificationRetryAtIsNullOrderByQueuePositionAscCreatedAtAsc(
                    TaskStateEnum.READY_FOR_QUALIFICATION,
                ),
            )
            // Retried tasks where backoff has elapsed
            emitAll(
                taskRepository.findByStateAndNextQualificationRetryAtLessThanEqualOrderByQueuePositionAscCreatedAtAsc(
                    TaskStateEnum.READY_FOR_QUALIFICATION,
                    Instant.now(),
                ),
            )
        }.filter { it.type != TaskTypeEnum.USER_TASK }

    /**
     * Atomically claim a task for qualification using MongoDB findAndModify.
     * This ensures that only ONE worker can claim a specific task, even in concurrent scenarios.
     *
     * SINGLETON GUARANTEE (Level 4 - per-task atomicity):
     * - Uses MongoDB findAndModify with state check (READY_FOR_QUALIFICATION -> QUALIFYING)
     * - Atomic operation ensures only one thread/process can claim the task
     * - If a task is already claimed (not READY_FOR_QUALIFICATION), returns null
     * - This works even across multiple application instances (distributed lock)
     *
     * Returns: Updated task with QUALIFYING state if successfully claimed, null if already claimed
     */
    suspend fun setToQualifying(task: TaskDocument): TaskDocument? {
        // Custom findAndModify that also sets qualificationStartedAt and clears old steps
        val query = Query(
            Criteria.where("_id").`is`(task.id.value)
                .and("state").`is`(TaskStateEnum.READY_FOR_QUALIFICATION.name),
        )
        val now = Instant.now()
        val update = Update()
            .set("state", TaskStateEnum.QUALIFYING.name)
            .set("qualificationStartedAt", now)
            .set("qualificationSteps", emptyList<Any>())
        val options = FindAndModifyOptions.options().returnNew(true)

        val result = mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull()

        if (result != null) {
            logger.info {
                "TASK_STATE_TRANSITION: id=${task.id} correlationId=${task.correlationId} " +
                    "from=READY_FOR_QUALIFICATION to=QUALIFYING type=${task.type} (ATOMICALLY CLAIMED)"
            }
        } else {
            logger.debug {
                "TASK_CLAIM_FAILED: id=${task.id} - already claimed by another instance"
            }
        }

        return result
    }

    /**
     * Append a qualification progress step to the task's history.
     * Uses MongoDB $push for atomic append without race conditions.
     */
    suspend fun appendQualificationStep(taskId: TaskId, step: com.jervis.entity.QualificationStepRecord) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().push("qualificationSteps", step)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Append an orchestrator progress step to the task's history.
     * Uses MongoDB $push for atomic append without race conditions.
     */
    suspend fun appendOrchestratorStep(taskId: TaskId, step: com.jervis.entity.OrchestratorStepRecord) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().push("orchestratorSteps", step)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Atomically claim a task for GPU execution using MongoDB findAndModify.
     * Returns null if the task was already claimed by another instance.
     */
    suspend fun claimForExecution(task: TaskDocument): TaskDocument? {
        val result = atomicStateTransition(
            taskId = task.id,
            expectedState = TaskStateEnum.READY_FOR_GPU,
            newState = TaskStateEnum.DISPATCHED_GPU,
        )

        if (result != null) {
            logger.info {
                "TASK_STATE_TRANSITION: id=${task.id} correlationId=${task.correlationId} " +
                    "from=READY_FOR_GPU to=DISPATCHED_GPU type=${task.type} (ATOMICALLY CLAIMED)"
            }
        } else {
            logger.debug {
                "TASK_CLAIM_FAILED: id=${task.id} - already claimed by another instance"
            }
        }

        return result
    }

    /**
     * Atomic state transition using MongoDB findAndModify.
     * Only succeeds if the task is currently in expectedState.
     * Returns the updated document, or null if the task was already in a different state.
     */
    private suspend fun atomicStateTransition(
        taskId: TaskId,
        expectedState: TaskStateEnum,
        newState: TaskStateEnum,
    ): TaskDocument? {
        val query = Query(
            Criteria.where("_id").`is`(taskId.value)
                .and("state").`is`(expectedState.name),
        )
        val update = Update().set("state", newState.name)
        val options = FindAndModifyOptions.options().returnNew(true)

        return mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull()
    }

    /**
     * Reset stale tasks stuck in transient states after pod crash/restart.
     * DISPATCHED_GPU older than threshold → READY_FOR_GPU (BACKGROUND tasks only)
     * QUALIFYING older than threshold → READY_FOR_QUALIFICATION
     * Returns the number of tasks reset.
     *
     * NOTE: FOREGROUND tasks in DISPATCHED_GPU are completed chat tasks, NOT stuck tasks.
     * They must remain in DISPATCHED_GPU to prevent re-execution after restart.
     */
    suspend fun resetStaleTasks(): Int {
        var resetCount = 0

        // Migration: set processingMode on old tasks that don't have the field
        val fixModeQuery = Query(Criteria.where("processingMode").exists(false))
        val fixModeUpdate = Update().set("processingMode", ProcessingMode.BACKGROUND.name)
        val fixResult = mongoTemplate.updateMulti(fixModeQuery, fixModeUpdate, TaskDocument::class.java).awaitSingle()
        if (fixResult.modifiedCount > 0) {
            logger.warn { "MIGRATION: Set processingMode=BACKGROUND on ${fixResult.modifiedCount} tasks missing the field" }
        }

        // Migration: BACKGROUND + DISPATCHED_GPU + no orchestratorThreadId → DONE
        // These are old info_only/simple_action tasks that were terminal but used DISPATCHED_GPU before DONE existed
        val migrateDoneQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.DISPATCHED_GPU.name)
                .and("processingMode").`is`(ProcessingMode.BACKGROUND.name)
                .and("orchestratorThreadId").exists(false),
        )
        val migrateDoneUpdate = Update().set("state", TaskStateEnum.DONE.name)
        val migrateDoneResult = mongoTemplate.updateMulti(migrateDoneQuery, migrateDoneUpdate, TaskDocument::class.java)
            .awaitSingle()
        if (migrateDoneResult.modifiedCount > 0) {
            logger.warn { "MIGRATION: Migrated ${migrateDoneResult.modifiedCount} BACKGROUND DISPATCHED_GPU tasks (no orchestratorThreadId) → DONE" }
        }

        // Reset DISPATCHED_GPU → READY_FOR_GPU (BACKGROUND tasks only, with orchestratorThreadId = truly dispatched)
        // FOREGROUND tasks stay DISPATCHED_GPU - they're completed, not stuck
        // DONE tasks are terminal — never reset
        val dispatchedQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.DISPATCHED_GPU.name)
                .and("processingMode").`is`(ProcessingMode.BACKGROUND.name),
        )
        val dispatchedUpdate = Update()
            .set("state", TaskStateEnum.READY_FOR_GPU.name)
            .unset("nextDispatchRetryAt")
            .set("dispatchRetryCount", 0)
        val dispatchedResult = mongoTemplate.updateMulti(dispatchedQuery, dispatchedUpdate, TaskDocument::class.java)
            .awaitSingle()
        val dispatchedCount = dispatchedResult.modifiedCount.toInt()
        resetCount += dispatchedCount

        if (dispatchedCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $dispatchedCount BACKGROUND DISPATCHED_GPU tasks → READY_FOR_GPU (FOREGROUND tasks excluded)" }
        }

        // Reset QUALIFYING → READY_FOR_QUALIFICATION
        val qualifyingQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.QUALIFYING.name),
        )
        val qualifyingUpdate = Update().set("state", TaskStateEnum.READY_FOR_QUALIFICATION.name)
        val qualifyingResult = mongoTemplate.updateMulti(qualifyingQuery, qualifyingUpdate, TaskDocument::class.java)
            .awaitSingle()
        val qualifyingCount = qualifyingResult.modifiedCount.toInt()
        resetCount += qualifyingCount

        if (qualifyingCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $qualifyingCount QUALIFYING tasks → READY_FOR_QUALIFICATION" }
        }

        // Reset ALL PYTHON_ORCHESTRATING → READY_FOR_GPU on pod restart.
        // After restart we lose in-memory heartbeat tracking, so we can't verify liveness.
        // If Python orchestrator IS still running, it will get ignored callback and task re-dispatches.
        val orchestratingQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.PYTHON_ORCHESTRATING.name),
        )
        val orchestratingUpdate = Update()
            .set("state", TaskStateEnum.READY_FOR_GPU.name)
            .unset("orchestratorThreadId")
            .unset("orchestrationStartedAt")
            .unset("nextDispatchRetryAt")
            .set("dispatchRetryCount", 0)
        val orchestratingResult = mongoTemplate.updateMulti(orchestratingQuery, orchestratingUpdate, TaskDocument::class.java)
            .awaitSingle()
        val orchestratingCount = orchestratingResult.modifiedCount.toInt()
        resetCount += orchestratingCount

        if (orchestratingCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $orchestratingCount PYTHON_ORCHESTRATING tasks → READY_FOR_GPU" }
        }

        // Reset ERROR indexing tasks → READY_FOR_QUALIFICATION (KB retry, max 2 recoveries)
        // Uses errorRecoveryCount field to prevent infinite ERROR→retry loops.
        // After 2 recoveries the task stays in ERROR for manual review.
        val indexingTypes = listOf(
            TaskTypeEnum.EMAIL_PROCESSING.name,
            TaskTypeEnum.BUGTRACKER_PROCESSING.name,
            TaskTypeEnum.GIT_PROCESSING.name,
            TaskTypeEnum.WIKI_PROCESSING.name,
            TaskTypeEnum.LINK_PROCESSING.name,
            TaskTypeEnum.MEETING_PROCESSING.name,
        )
        val errorIndexingQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.ERROR.name)
                .and("type").`in`(indexingTypes)
                .orOperator(
                    Criteria.where("errorRecoveryCount").exists(false),
                    Criteria.where("errorRecoveryCount").lt(2),
                ),
        )
        val errorIndexingUpdate = Update()
            .set("state", TaskStateEnum.READY_FOR_QUALIFICATION.name)
            .unset("errorMessage")
            .inc("errorRecoveryCount", 1)
        val errorIndexingResult = mongoTemplate.updateMulti(errorIndexingQuery, errorIndexingUpdate, TaskDocument::class.java)
            .awaitSingle()
        val errorIndexingCount = errorIndexingResult.modifiedCount.toInt()
        resetCount += errorIndexingCount

        if (errorIndexingCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $errorIndexingCount ERROR indexing tasks → READY_FOR_QUALIFICATION (KB retry)" }
        }

        return resetCount
    }

    /**
     * Recover stuck tasks when the KB qualification queue is empty.
     * Called by TaskQualificationService after a cycle finds no work.
     *
     * Recovers:
     * 1. ERROR indexing tasks → READY_FOR_QUALIFICATION (max 2 recoveries, then stays ERROR)
     * 2. QUALIFYING tasks stuck >10 min → READY_FOR_QUALIFICATION (error handler failure)
     */
    suspend fun recoverStuckIndexingTasks(): Int {
        var count = 0

        // 1. ERROR indexing tasks → retry (max 2 recoveries to prevent infinite loops)
        val indexingTypes = listOf(
            TaskTypeEnum.EMAIL_PROCESSING.name,
            TaskTypeEnum.BUGTRACKER_PROCESSING.name,
            TaskTypeEnum.GIT_PROCESSING.name,
            TaskTypeEnum.WIKI_PROCESSING.name,
            TaskTypeEnum.LINK_PROCESSING.name,
            TaskTypeEnum.MEETING_PROCESSING.name,
        )
        val errorQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.ERROR.name)
                .and("type").`in`(indexingTypes)
                .orOperator(
                    Criteria.where("errorRecoveryCount").exists(false),
                    Criteria.where("errorRecoveryCount").lt(2),
                ),
        )
        val errorUpdate = Update()
            .set("state", TaskStateEnum.READY_FOR_QUALIFICATION.name)
            .unset("errorMessage")
            .inc("errorRecoveryCount", 1)
        val errorResult = mongoTemplate.updateMulti(errorQuery, errorUpdate, TaskDocument::class.java).awaitSingle()
        val errorCount = errorResult.modifiedCount.toInt()
        count += errorCount
        if (errorCount > 0) {
            logger.warn { "EMPTY_QUEUE_RECOVERY: Reset $errorCount ERROR indexing tasks → READY_FOR_QUALIFICATION" }
        }

        // 2. QUALIFYING tasks stuck >10 min (error handler failed, task left in QUALIFYING)
        val stuckThreshold = Instant.now().minus(Duration.ofMinutes(10))
        val stuckQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.QUALIFYING.name)
                .and("qualificationStartedAt").lt(stuckThreshold),
        )
        val stuckUpdate = Update()
            .set("state", TaskStateEnum.READY_FOR_QUALIFICATION.name)
            .unset("qualificationStartedAt")
        val stuckResult = mongoTemplate.updateMulti(stuckQuery, stuckUpdate, TaskDocument::class.java).awaitSingle()
        val stuckCount = stuckResult.modifiedCount.toInt()
        count += stuckCount
        if (stuckCount > 0) {
            logger.warn { "EMPTY_QUEUE_RECOVERY: Reset $stuckCount stuck QUALIFYING tasks (>10min) → READY_FOR_QUALIFICATION" }
        }

        return count
    }

    /**
     * Clean content through Tika to remove HTML, XML, Confluence/Jira markup.
     * Detects content type and applies appropriate extraction.
     */
    private suspend fun cleanContentWithTika(
        content: String,
        correlationId: String,
        taskType: TaskTypeEnum,
    ): String {
        val fileName =
            when (taskType) {
                TaskTypeEnum.WIKI_PROCESSING -> "wiki-$correlationId.xml"
                TaskTypeEnum.BUGTRACKER_PROCESSING -> "bugtracker-$correlationId.html"
                else -> "content-$correlationId.txt"
            }

        return try {
            logger.debug { "Cleaning content for $correlationId (type=$taskType), original length: ${content.length}" }
            val cleaned =
                tikaTextExtractionService.extractPlainText(
                    content = content,
                    fileName = fileName,
                )

            if (cleaned.isBlank() && content.isNotBlank()) {
                logger.warn {
                    "Tika returned empty content for $correlationId (type=$taskType). Using original content to prevent data loss."
                }
                return content
            }

            logger.debug { "Cleaned content for $correlationId, new length: ${cleaned.length}" }
            cleaned
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clean content through Tika for $correlationId, using original" }
            content
        }
    }
}
