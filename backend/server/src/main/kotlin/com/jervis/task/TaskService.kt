package com.jervis.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.ProcessingMode
import com.jervis.task.TaskDocument
import com.jervis.infrastructure.config.properties.QualifierProperties
import com.jervis.client.ClientRepository
import com.jervis.task.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.rpc.SubTaskRequest
import com.jervis.infrastructure.llm.DocumentExtractionClient
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
    private val documentExtractionClient: DocumentExtractionClient,
    private val qualifierProperties: QualifierProperties,
    @Lazy private val notificationRpc: NotificationRpcImpl,
    @Lazy private val chatRpcImpl: com.jervis.chat.ChatRpcImpl,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    // Track current running task for queue status
    @Volatile
    private var currentRunningTask: TaskDocument? = null

    /**
     * Bulk-mark all pipeline tasks for archived clients as DONE.
     * Targets: INDEXING, QUEUED.
     * Scheduled tasks (NEW) are left untouched — they resume when client is unarchived.
     * Called on startup and periodically (every 5 min) from BackgroundEngine.
     */
    suspend fun markArchivedClientTasksAsDone(): Int {
        val archivedIds = clientRepository.findByArchivedTrue().toList().map { it.id.value }
        if (archivedIds.isEmpty()) return 0

        val pipelineStates = listOf(
            TaskStateEnum.INDEXING.name,
            TaskStateEnum.QUEUED.name,
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
        state: TaskStateEnum = TaskStateEnum.INDEXING,
        attachments: List<AttachmentMetadata> = emptyList(),
        taskName: String? = null,
        hasAttachments: Boolean = false,
        attachmentCount: Int = 0,
        scheduledAt: java.time.Instant? = null,
        cronTimezone: String? = null,
        followUserTimezone: Boolean = false,
        scheduledLocalTime: String? = null,
        mentionsJervis: Boolean = false,
        topicId: String? = null,
        meetingMetadata: MeetingMetadata? = null,
        deadline: java.time.Instant? = null,
        userPresence: String? = null,
    ): TaskDocument {
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val cleanContent = cleanHtmlContent(content, correlationId, sourceUrn)

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
                hasAttachments = hasAttachments,
                attachmentCount = attachmentCount,
                scheduledAt = scheduledAt,
                cronTimezone = cronTimezone,
                followUserTimezone = followUserTimezone,
                scheduledLocalTime = scheduledLocalTime,
                mentionsJervis = mentionsJervis,
                topicId = topicId,
                meetingMetadata = meetingMetadata,
                deadline = deadline,
                userPresence = userPresence,
            )

        val saved = taskRepository.save(task)

        if (saved.type != TaskTypeEnum.SYSTEM) {
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
                    TaskTypeEnum.SCHEDULED,
                )?.let {
                    emit(it)
                }
            emitAll(taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.QUEUED))
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
                TaskStateEnum.QUEUED,
            ).firstOrNull { it.nextDispatchRetryAt == null || it.nextDispatchRetryAt <= now }
    }

    /**
     * Get next BACKGROUND task — deadline-first, then priority, then FIFO.
     *
     * Ordering (all in DB, no application-level filter):
     *   1. deadline ASC — nearest deadline wins (expired deadlines sort first among non-null,
     *      null deadlines sort last so non-urgent work flows behind urgent tasks).
     *   2. priorityScore DESC — tie-break for equal deadlines (or all-null bucket).
     *   3. createdAt ASC — FIFO within everything else.
     *
     * No watchdog / priority-bump loop — the sort key does the work.
     */
    suspend fun getNextBackgroundTask(): TaskDocument? {
        val now = java.time.Instant.now()
        return taskRepository
            .findByProcessingModeAndStateOrderByDeadlineAscPriorityScoreDescCreatedAtAsc(
                ProcessingMode.BACKGROUND,
                TaskStateEnum.QUEUED,
            ).firstOrNull { it.nextDispatchRetryAt == null || it.nextDispatchRetryAt <= now }
    }

    /**
     * Get next IDLE task (system idle work) ordered by createdAt ASC.
     *
     * IDLE tasks are lowest priority — only picked when no FOREGROUND or BACKGROUND tasks exist.
     * Returns only tasks with processingMode=IDLE in QUEUED state.
     */
    suspend fun getNextIdleTask(): TaskDocument? {
        val now = java.time.Instant.now()
        return taskRepository
            .findByProcessingModeAndStateOrderByCreatedAtAsc(
                ProcessingMode.IDLE,
                TaskStateEnum.QUEUED,
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
     * for PROCESSING tasks (dispatched to orchestrator but still active).
     */
    suspend fun getCurrentRunningTask(): TaskDocument? =
        currentRunningTask ?: getOrchestratingTask()

    /**
     * Find any task currently being processed by the Python orchestrator.
     * Covers both FOREGROUND and BACKGROUND tasks in PROCESSING state.
     */
    private suspend fun getOrchestratingTask(): TaskDocument? =
        taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.PROCESSING)
            .toList()
            .firstOrNull()

    /**
     * Get queue status: currently running task and queue size.
     * Queue size counts only FOREGROUND tasks in QUEUED state.
     * Running task is atomically claimed (PROCESSING) so it's excluded automatically.
     */
    suspend fun getGlobalQueueStatus(): Pair<TaskDocument?, Int> {
        val rawCount =
            taskRepository.countByProcessingModeAndState(
                processingMode = ProcessingMode.FOREGROUND,
                state = TaskStateEnum.QUEUED,
            )
        // Exclude the currently running task from the count (its DB state is still QUEUED)
        val running = getCurrentRunningTask()
        val isRunningCounted = running != null &&
            running.processingMode == ProcessingMode.FOREGROUND &&
            running.state == TaskStateEnum.QUEUED
        val queueSize = if (isRunningCounted) (rawCount - 1).coerceAtLeast(0) else rawCount

        logger.debug {
            "GET_GLOBAL_QUEUE_STATUS | " +
                "rawCount=$rawCount | queueSize=$queueSize | hasRunningTask=${running != null} | isRunningCounted=$isRunningCounted"
        }
        return running to queueSize.toInt()
    }

    /**
     * Get ALL pending FOREGROUND tasks for queue display (global, not per-client).
     * Returns only tasks actually waiting to be processed (QUEUED).
     * PROCESSING tasks are NOT in the queue — they're shown in the Agent section.
     */
    suspend fun getPendingForegroundTasks(): List<TaskDocument> {
        val running = currentRunningTask
        return taskRepository
            .findByProcessingModeAndStateOrderByQueuePositionAsc(
                ProcessingMode.FOREGROUND,
                TaskStateEnum.QUEUED,
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
                TaskStateEnum.QUEUED,
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
            .and("state").`is`(TaskStateEnum.QUEUED.name)

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
            ProcessingMode.BACKGROUND, ProcessingMode.IDLE -> getPendingBackgroundTasks()
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
            ProcessingMode.BACKGROUND, ProcessingMode.IDLE -> getPendingBackgroundTasks()
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

        // Phase 3 re-entrant qualifier: when a task reaches DONE, walk up the
        // parent chain and unblock any parent whose children are now all DONE.
        // Unblocked parents are flagged for re-qualification so the qualifier
        // sees the new evidence.
        if (next == TaskStateEnum.DONE && saved.parentTaskId != null) {
            try {
                unblockChildrenOfParent(saved.parentTaskId)
            } catch (e: Exception) {
                logger.warn(e) { "PARENT_UNBLOCK_FAILED: childId=${saved.id} parentId=${saved.parentTaskId}: ${e.message}" }
            }
        }

        // Phase 5 stream-based sidebar: push TASK_LIST_CHANGED so the UI
        // sidebar refreshes immediately. No polling.
        try {
            chatRpcImpl.emitTaskListChanged(taskId = task.id.toString(), newState = next.name)
        } catch (e: Exception) {
            logger.debug { "emitTaskListChanged failed (non-critical): ${e.message}" }
        }

        return saved
    }

    /**
     * Phase 3 re-entrant qualifier: when one of a parent's children completes,
     * check whether *all* children are DONE. If so, transition the parent from
     * BLOCKED back to a re-qualifiable state and set [needsQualification]=true
     * so the [RequalificationLoop] picks it up with the new context.
     *
     * Idempotent — safe to call multiple times. Walks up to the root if the
     * unblocked parent itself is the child of another BLOCKED parent.
     */
    suspend fun unblockChildrenOfParent(parentTaskId: TaskId) {
        val parent = taskRepository.getById(parentTaskId) ?: return
        if (parent.state != TaskStateEnum.BLOCKED) {
            return
        }
        val pendingChildren = taskRepository.countByParentTaskIdAndStateNot(parentTaskId, TaskStateEnum.DONE)
        if (pendingChildren > 0L) {
            logger.debug { "PARENT_STILL_BLOCKED: parentId=$parentTaskId pendingChildren=$pendingChildren" }
            return
        }
        // All children DONE → unblock + flag for re-qualification.
        val query = Query(Criteria.where("_id").`is`(parentTaskId.value))
        val update = Update()
            .set("state", TaskStateEnum.NEW.name)
            .set("needsQualification", true)
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            TaskDocument::class.java,
        ).awaitSingleOrNull()
        logger.info { "PARENT_UNBLOCKED: parentId=$parentTaskId — all children DONE, flagged for re-qualification" }
    }

    /**
     * Phase 3: explicitly mark a task as needing (re-)qualification. Used by
     * the user-response handler when a USER_TASK comes back with new info.
     */
    suspend fun markNeedsQualification(taskId: TaskId) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().set("needsQualification", true)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
        logger.debug { "MARK_NEEDS_QUALIFICATION: id=$taskId" }
    }

    /**
     * Save qualifier-generated summary to the task document.
     * Called from the `/internal/qualification-done` callback after the qualifier
     * produces a context summary or reason. Capped at 500 characters.
     */
    suspend fun saveSummary(taskId: TaskId, summary: String) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().set("summary", summary)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Phase 3: clear the re-qualification flag once the qualifier has produced
     * a decision. Called from the `/internal/qualification-done` callback.
     */
    suspend fun clearNeedsQualification(taskId: TaskId) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().set("needsQualification", false)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Phase 3 ESCALATE decision: transition the existing task to USER_TASK
     * with the qualifier-supplied question and context. Never creates a wrapper
     * task — preserves the original task's `type`, `correlationId`, and
     * `sourceUrn`. Surfaces in K reakci via the state-only query.
     */
    suspend fun transitionToUserTask(
        task: TaskDocument,
        pendingQuestion: String,
        questionContext: String?,
    ): TaskDocument {
        val query = Query(Criteria.where("_id").`is`(task.id.value))
        val update = Update()
            .set("state", TaskStateEnum.USER_TASK.name)
            .set("pendingUserQuestion", pendingQuestion)
            .set("userQuestionContext", questionContext)
            .set("needsQualification", false)
            .set("lastActivityAt", Instant.now())
        val saved = mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            TaskDocument::class.java,
        ).awaitSingleOrNull() ?: task.copy(state = TaskStateEnum.USER_TASK)
        logger.info { "TASK_ESCALATED_TO_USER: id=${task.id} question=${pendingQuestion.take(80)}" }
        return saved
    }

    /**
     * Phase 3 DECOMPOSE decision: create child TaskDocuments under the given
     * parent and move the parent to BLOCKED. The parent's `blockedByTaskIds`
     * holds the new child IDs. When the last child reaches DONE,
     * [unblockChildrenOfParent] flips the parent back to NEW with
     * `needsQualification=true` so the qualifier sees the children's results
     * and decides the next step.
     *
     * Children inherit the parent's `clientId`, `projectId`, `correlationId`,
     * and `sourceUrn` so KB graph relationships and the K reakci scope filter
     * still work. Each child starts in `state=NEW` with
     * `needsQualification=true` so the loop picks them up immediately.
     */
    suspend fun decomposeTask(
        parent: TaskDocument,
        subTasks: List<SubTaskRequest>,
    ): List<TaskId> {
        require(subTasks.isNotEmpty()) { "decomposeTask called with empty sub-task list" }
        val now = Instant.now()
        val childIds = subTasks.map { req ->
            val child = TaskDocument(
                type = parent.type,
                taskName = req.taskName.take(200),
                content = req.content,
                projectId = parent.projectId,
                clientId = parent.clientId,
                createdAt = now,
                state = TaskStateEnum.NEW,
                processingMode = parent.processingMode,
                correlationId = parent.correlationId,
                sourceUrn = parent.sourceUrn,
                parentTaskId = parent.id,
                phase = req.phase,
                orderInPhase = req.orderInPhase,
                needsQualification = true,
            )
            taskRepository.save(child).id
        }

        // Transition parent → BLOCKED with the new child IDs.
        val parentQuery = Query(Criteria.where("_id").`is`(parent.id.value))
        val parentUpdate = Update()
            .set("state", TaskStateEnum.BLOCKED.name)
            .set("blockedByTaskIds", childIds.map { it.value })
            .set("needsQualification", false)
            .set("lastActivityAt", now)
        mongoTemplate.findAndModify(
            parentQuery,
            parentUpdate,
            FindAndModifyOptions.options().returnNew(true),
            TaskDocument::class.java,
        ).awaitSingleOrNull()

        logger.info {
            "TASK_DECOMPOSED: parentId=${parent.id} children=${childIds.size} ids=${childIds.map { it.toString().take(8) }}"
        }
        return childIds
    }

    /**
     * Update task state and content atomically.
     * Used by WorkPlanExecutor to complete/escalate root tasks with summary content.
     */
    suspend fun updateStateAndContent(
        task: TaskDocument,
        next: TaskStateEnum,
        content: String,
    ): TaskDocument {
        val fromState = task.state
        val query = Query(Criteria.where("_id").`is`(task.id.value))
        val update = Update()
            .set("state", next.name)
            .set("content", content)
        val options = FindAndModifyOptions.options().returnNew(true)
        val saved = mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull() ?: task.copy(state = next, content = content)
        logger.info {
            "TASK_STATE_CONTENT_UPDATE: id=${task.id} from=$fromState to=$next type=${task.type}"
        }
        return saved
    }

    /**
     * Save KB result fields to task document after KB processing completes.
     */
    suspend fun saveKbResult(
        taskId: TaskId,
        kbSummary: String,
        kbEntities: List<String>,
        kbActionable: Boolean,
    ) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update()
            .set("kbSummary", kbSummary)
            .set("kbEntities", kbEntities)
            .set("kbActionable", kbActionable)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Save qualification agent result fields to task document.
     * Called from /internal/qualification-done callback after Python LLM agent finishes.
     */
    suspend fun saveQualificationResult(
        taskId: TaskId,
        priorityScore: Int,
        priorityReason: String,
        actionType: String,
        estimatedComplexity: String,
        qualifierContext: String,
    ) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update()
            .set("priorityScore", priorityScore)
            .set("priorityReason", priorityReason)
            .set("actionType", actionType)
            .set("estimatedComplexity", estimatedComplexity)
            .set("qualifierPreparedContext", qualifierContext)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
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
     * Return the task to INDEXING queue for retry after KB dispatch failure.
     * Uses DB-based exponential backoff: 1s, 2s, 4s, 8s, ... up to 5min, then stays at 5min forever.
     * Operational errors (timeout, connection refused) never mark as ERROR - they keep retrying.
     * The task is stored in DB with a future nextQualificationRetryAt timestamp.
     */
    suspend fun returnToIndexingQueue(task: TaskDocument) {
        // Reload from DB to get current state (caller may have stale in-memory object)
        val current = task.id?.let { taskRepository.getById(it) } ?: task
        if (current.state != TaskStateEnum.INDEXING) {
            logger.warn {
                "Cannot return task to indexing queue - expected INDEXING but was ${current.state}: ${current.id}"
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

        // Release the claim and set backoff — task stays INDEXING, ready for retry
        val query = Query(Criteria.where("_id").`is`(current.id.value))
        val update = Update()
            .unset("indexingClaimedAt")
            .set("qualificationRetries", newRetryCount)
            .set("nextQualificationRetryAt", nextRetryAt)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()

        logger.info {
            "TASK_RETURNED_TO_INDEXING_QUEUE: id=${current.id} correlationId=${current.correlationId} " +
                "retry=$newRetryCount backoffMs=$backoffMs nextRetryAt=$nextRetryAt"
        }
    }

    /**
     * Return all UNCLAIMED INDEXING tasks eligible for KB dispatch now:
     * - INDEXING where indexingClaimedAt is null (unclaimed)
     * - AND nextQualificationRetryAt is null (new tasks) OR <= now (backoff expired)
     *
     * Tasks already claimed (indexingClaimedAt != null) or with future backoff are hidden.
     * Order: queuePosition ASC NULLS LAST (manually prioritized first), then createdAt ASC (FIFO).
     */
    suspend fun findTasksForIndexing(): Flow<TaskDocument> =
        flow {
            val now = Instant.now()
            // Phase 4 fix: derived query replacement for the previous Criteria-
            // based call which was silently returning 0 even when matching
            // INDEXING tasks existed. See TaskRepository for the rationale.
            val newCount = mutableListOf<TaskDocument>()
            taskRepository.findByStateAndIndexingClaimedAtIsNullAndNextQualificationRetryAtIsNullOrderByCreatedAtAsc(
                TaskStateEnum.INDEXING,
            ).collect { newCount.add(it) }
            if (newCount.isNotEmpty()) {
                logger.info { "INDEXING_FIND: branch=new found=${newCount.size}" }
            }
            newCount.forEach { emit(it) }

            // Phase 4 fix: derived query for backoff-elapsed retried tasks.
            val retriedCount = mutableListOf<TaskDocument>()
            taskRepository
                .findByStateAndIndexingClaimedAtIsNullAndNextQualificationRetryAtLessThanEqualOrderByCreatedAtAsc(
                    TaskStateEnum.INDEXING,
                    now,
                ).collect { retriedCount.add(it) }
            if (retriedCount.isNotEmpty()) {
                logger.info { "INDEXING_FIND: branch=retried found=${retriedCount.size}" }
            }
            retriedCount.forEach { emit(it) }
        }
        // CRITICAL Phase 1 regression fix: previously this flow had
        //     .filter { it.type != TaskTypeEnum.SYSTEM }
        // which silently dropped EVERY indexing task because, after Phase 1,
        // all indexer-created tasks have type=SYSTEM. The filter was a leftover
        // from the 15-value enum era and made the qualifier loop a no-op for
        // all real work. The filter is now removed.

    /**
     * Atomically claim an INDEXING task for KB dispatch using MongoDB findAndModify.
     * State stays INDEXING — claim is tracked via indexingClaimedAt timestamp.
     *
     * SINGLETON GUARANTEE (Level 4 - per-task atomicity):
     * - Uses MongoDB findAndModify with state + indexingClaimedAt=null check
     * - Atomic operation ensures only one thread/process can claim the task
     * - If already claimed (indexingClaimedAt != null), returns null
     * - Works across multiple application instances (distributed lock)
     *
     * Returns: Updated task with indexingClaimedAt set if successfully claimed, null if already claimed
     */
    suspend fun claimForIndexing(task: TaskDocument): TaskDocument? {
        val query = Query(
            Criteria.where("_id").`is`(task.id.value)
                .and("state").`is`(TaskStateEnum.INDEXING.name)
                .and("indexingClaimedAt").`is`(null),
        )
        val now = Instant.now()
        val update = Update()
            .set("indexingClaimedAt", now)
            .set("qualificationStartedAt", now)
            .set("qualificationSteps", emptyList<Any>())
        val options = FindAndModifyOptions.options().returnNew(true)

        val result = mongoTemplate.findAndModify(query, update, options, TaskDocument::class.java)
            .awaitSingleOrNull()

        if (result != null) {
            logger.info {
                "INDEXING_CLAIMED: id=${task.id} correlationId=${task.correlationId} " +
                    "type=${task.type} (ATOMICALLY CLAIMED via indexingClaimedAt)"
            }
        } else {
            logger.debug {
                "INDEXING_CLAIM_FAILED: id=${task.id} - already claimed by another instance"
            }
        }

        return result
    }

    /**
     * Append a qualification progress step to the task's history.
     * Uses MongoDB $push for atomic append without race conditions.
     */
    suspend fun appendQualificationStep(taskId: TaskId, step: com.jervis.task.QualificationStepRecord) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().push("qualificationSteps", step)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Append an orchestrator progress step to the task's history.
     * Uses MongoDB $push for atomic append without race conditions.
     */
    suspend fun appendOrchestratorStep(taskId: TaskId, step: com.jervis.task.OrchestratorStepRecord) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update().push("orchestratorSteps", step)
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
    }

    /**
     * Set topicId and lastActivityAt on a newly created task (for thread/topic consolidation).
     */
    suspend fun setTopicId(taskId: TaskId, topicId: String) {
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update()
            .set("topicId", topicId)
            .set("lastActivityAt", Instant.now())
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
        logger.debug { "SET_TOPIC_ID: id=$taskId topicId=$topicId" }
    }

    /**
     * Update an existing thread task with new activity — appends content summary and bumps lastActivityAt.
     * Used by thread consolidation: new messages in an existing conversation update the task instead of creating new ones.
     */
    suspend fun updateThreadActivity(taskId: TaskId, appendContent: String) {
        val now = Instant.now()
        val query = Query(Criteria.where("_id").`is`(taskId.value))
        val update = Update()
            .set("lastActivityAt", now)
            .set("content", appendContent) // Replace with latest thread summary
        mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
        logger.info { "THREAD_ACTIVITY_UPDATE: id=$taskId lastActivityAt=$now" }
    }

    /**
     * Auto-resolve a USER_TASK as handled externally (e.g., user replied from mobile).
     * Only resolves tasks in USER_TASK state — tasks in other states continue processing normally.
     *
     * @return true if task was resolved, false if task was in a different state
     */
    suspend fun resolveAsHandledExternally(taskId: TaskId): Boolean {
        // Resolve any active task — user replied externally, task is no longer needed
        val activeStates = listOf(
            TaskStateEnum.INDEXING, TaskStateEnum.QUEUED,
            TaskStateEnum.PROCESSING, TaskStateEnum.USER_TASK, TaskStateEnum.BLOCKED,
        ).map { it.name }
        val query = Query(
            Criteria.where("_id").`is`(taskId.value)
                .and("state").`in`(activeStates),
        )
        val update = Update()
            .set("state", TaskStateEnum.DONE.name)
            .set("errorMessage", "Auto-resolved: user replied externally")
        val result = mongoTemplate.updateFirst(query, update, TaskDocument::class.java).awaitSingle()
        val resolved = result.modifiedCount > 0
        if (resolved) {
            logger.info { "THREAD_AUTO_RESOLVED: id=$taskId (user replied externally)" }
        }
        return resolved
    }

    /**
     * Atomically claim a task for GPU execution using MongoDB findAndModify.
     * Returns null if the task was already claimed by another instance.
     */
    suspend fun claimForExecution(task: TaskDocument): TaskDocument? {
        val result = atomicStateTransition(
            taskId = task.id,
            expectedState = TaskStateEnum.QUEUED,
            newState = TaskStateEnum.PROCESSING,
        )

        if (result != null) {
            logger.info {
                "TASK_STATE_TRANSITION: id=${task.id} correlationId=${task.correlationId} " +
                    "from=QUEUED to=PROCESSING type=${task.type} (ATOMICALLY CLAIMED)"
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
     * PROCESSING older than threshold → QUEUED (BACKGROUND tasks only)
     * Returns the number of tasks reset.
     *
     * NOTE: FOREGROUND tasks in PROCESSING are completed chat tasks (now DONE).
     * Only BACKGROUND/IDLE PROCESSING tasks are reset.
     */
    suspend fun resetStaleTasks(): Int {
        var resetCount = 0

        // Migrate any legacy QUALIFYING tasks → INDEXING (state removed in pipeline unification)
        val legacyQuery = Query(Criteria.where("state").`is`("QUALIFYING"))
        val legacyUpdate = Update()
            .set("state", TaskStateEnum.INDEXING.name)
            .unset("indexingClaimedAt")
        val legacyResult = mongoTemplate.updateMulti(legacyQuery, legacyUpdate, TaskDocument::class.java)
            .awaitSingle()
        if (legacyResult.modifiedCount > 0) {
            logger.warn { "STALE_RECOVERY: Migrated ${legacyResult.modifiedCount} legacy QUALIFYING tasks → INDEXING" }
            resetCount += legacyResult.modifiedCount.toInt()
        }

        // Release stale indexing claims (KB dispatch in progress when pod crashed)
        val staleClaimQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.INDEXING.name)
                .and("indexingClaimedAt").ne(null),
        )
        val staleClaimUpdate = Update()
            .unset("indexingClaimedAt")
            .unset("qualificationStartedAt")
        val staleClaimResult = mongoTemplate.updateMulti(staleClaimQuery, staleClaimUpdate, TaskDocument::class.java)
            .awaitSingle()
        val staleClaimCount = staleClaimResult.modifiedCount.toInt()
        resetCount += staleClaimCount

        if (staleClaimCount > 0) {
            logger.warn { "STALE_RECOVERY: Released $staleClaimCount stale INDEXING claims" }
        }

        // Reset ALL PROCESSING → QUEUED on pod restart.
        // If Python orchestrator IS still running, it will get ignored callback and task re-dispatches.
        val orchestratingQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.PROCESSING.name),
        )
        val orchestratingUpdate = Update()
            .set("state", TaskStateEnum.QUEUED.name)
            .unset("orchestratorThreadId")
            .unset("orchestrationStartedAt")
            .unset("nextDispatchRetryAt")
            .set("dispatchRetryCount", 0)
        val orchestratingResult = mongoTemplate.updateMulti(orchestratingQuery, orchestratingUpdate, TaskDocument::class.java)
            .awaitSingle()
        val orchestratingCount = orchestratingResult.modifiedCount.toInt()
        resetCount += orchestratingCount

        if (orchestratingCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $orchestratingCount PROCESSING tasks → QUEUED" }
        }

        // Reset ERROR indexing tasks → INDEXING (KB retry, max 2 recoveries).
        // Uses errorRecoveryCount field to prevent infinite ERROR→retry loops.
        // After 2 recoveries the task stays in ERROR for manual review.
        // After Phase 1: indexing tasks are all SYSTEM type — source-specific
        // discrimination lives in sourceUrn, not type.
        val errorIndexingQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.ERROR.name)
                .and("type").`is`(TaskTypeEnum.SYSTEM.name)
                .orOperator(
                    Criteria.where("errorRecoveryCount").exists(false),
                    Criteria.where("errorRecoveryCount").lt(2),
                ),
        )
        val errorIndexingUpdate = Update()
            .set("state", TaskStateEnum.INDEXING.name)
            .unset("errorMessage")
            .inc("errorRecoveryCount", 1)
        val errorIndexingResult = mongoTemplate.updateMulti(errorIndexingQuery, errorIndexingUpdate, TaskDocument::class.java)
            .awaitSingle()
        val errorIndexingCount = errorIndexingResult.modifiedCount.toInt()
        resetCount += errorIndexingCount

        if (errorIndexingCount > 0) {
            logger.warn { "STALE_RECOVERY: Reset $errorIndexingCount ERROR indexing tasks → INDEXING (KB retry)" }
        }

        return resetCount
    }

    /**
     * Recover stuck tasks when the KB indexing queue is empty.
     * Called by TaskQualificationService after a cycle finds no work.
     *
     * Recovers:
     * 1. ERROR indexing tasks → INDEXING (max 2 recoveries, then stays ERROR)
     * 2. INDEXING tasks with stale indexingClaimedAt >10 min → release claim (KB callback never arrived)
     */
    suspend fun recoverStuckIndexingTasks(): Int {
        var count = 0

        // 1. ERROR indexing tasks → retry (max 2 recoveries to prevent infinite loops)
        val errorQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.ERROR.name)
                .and("type").`is`(TaskTypeEnum.SYSTEM.name)
                .orOperator(
                    Criteria.where("errorRecoveryCount").exists(false),
                    Criteria.where("errorRecoveryCount").lt(2),
                ),
        )
        val errorUpdate = Update()
            .set("state", TaskStateEnum.INDEXING.name)
            .unset("errorMessage")
            .inc("errorRecoveryCount", 1)
        val errorResult = mongoTemplate.updateMulti(errorQuery, errorUpdate, TaskDocument::class.java).awaitSingle()
        val errorCount = errorResult.modifiedCount.toInt()
        count += errorCount
        if (errorCount > 0) {
            logger.warn { "EMPTY_QUEUE_RECOVERY: Reset $errorCount ERROR indexing tasks → INDEXING" }
        }

        // 2. INDEXING tasks with stale claim >10 min (KB callback never arrived)
        val stuckThreshold = Instant.now().minus(Duration.ofMinutes(10))
        val stuckQuery = Query(
            Criteria.where("state").`is`(TaskStateEnum.INDEXING.name)
                .and("indexingClaimedAt").lt(stuckThreshold),
        )
        val stuckUpdate = Update()
            .unset("indexingClaimedAt")
            .unset("qualificationStartedAt")
        val stuckResult = mongoTemplate.updateMulti(stuckQuery, stuckUpdate, TaskDocument::class.java).awaitSingle()
        val stuckCount = stuckResult.modifiedCount.toInt()
        count += stuckCount
        if (stuckCount > 0) {
            logger.warn { "EMPTY_QUEUE_RECOVERY: Released $stuckCount stale INDEXING claims (>10min)" }
        }

        return count
    }

    /**
     * Clean content via document-extraction microservice.
     *
     * Routes to BeautifulSoup (HTML), lxml (XML), or returns as-is (plain text).
     * Wiki and bugtracker content arrives as HTML/XML and needs extraction.
     * Email/meeting/git content is already plain text — the service detects this and returns unchanged.
     */
    private suspend fun cleanHtmlContent(
        content: String,
        correlationId: String,
        sourceUrn: SourceUrn,
    ): String {
        // Source-specific MIME type for the document extractor — derived from
        // SourceUrn scheme (the post-Phase-1 source identifier).
        val mimeType = when (sourceUrn.scheme()) {
            "confluence" -> "application/xml"
            "jira", "github-issue", "gitlab-issue" -> "text/html"
            "email" -> "text/html"
            else -> "text/plain"
        }

        logger.debug { "Cleaning content for $correlationId (scheme=${sourceUrn.scheme()}, mime=$mimeType), original length: ${content.length}" }
        val cleaned = documentExtractionClient.extractText(content, mimeType)
        logger.debug { "Cleaned content for $correlationId, new length: ${cleaned.length}" }
        return cleaned
    }
}
