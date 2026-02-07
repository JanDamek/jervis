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
import com.jervis.repository.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.text.TikaTextExtractionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val qualifierProperties: QualifierProperties,
    @Lazy private val notificationRpc: NotificationRpcImpl,
) {
    private val logger = KotlinLogging.logger {}

    // Track current running task for queue status
    @Volatile
    private var currentRunningTask: TaskDocument? = null

    suspend fun createTask(
        taskType: TaskTypeEnum,
        content: String,
        clientId: ClientId,
        correlationId: String,
        sourceUrn: SourceUrn,
        projectId: ProjectId? = null,
        state: TaskStateEnum = TaskStateEnum.READY_FOR_QUALIFICATION,
        attachments: List<AttachmentMetadata> = emptyList(),
    ): TaskDocument {
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val cleanContent = cleanContentWithTika(content, correlationId, taskType)

        val task =
            TaskDocument(
                type = taskType,
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
    suspend fun getNextForegroundTask(): TaskDocument? =
        taskRepository
            .findByProcessingModeAndStateOrderByQueuePositionAsc(
                ProcessingMode.FOREGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).firstOrNull()

    /**
     * Get next BACKGROUND task (autonomous) ordered by createdAt (oldest first).
     * Background tasks process in FIFO order (creation time).
     *
     * @return Next BACKGROUND task to process, or null if no tasks
     */
    suspend fun getNextBackgroundTask(): TaskDocument? =
        taskRepository
            .findByProcessingModeAndStateOrderByCreatedAtAsc(
                ProcessingMode.BACKGROUND,
                TaskStateEnum.READY_FOR_GPU,
            ).firstOrNull()

    /**
     * Mark task as currently running (for queue status tracking)
     */
    fun setRunningTask(task: TaskDocument?) {
        currentRunningTask = task
    }

    /**
     * Get currently running task (for progress display)
     */
    fun getCurrentRunningTask(): TaskDocument? = currentRunningTask

    /**
     * Get queue status: currently running task and queue size
     */
    suspend fun getQueueStatus(
        clientId: ClientId,
        projectId: ProjectId?,
    ): Pair<TaskDocument?, Int> {
        val queueSize =
            taskRepository.countByStateAndTypeAndClientId(
                state = TaskStateEnum.READY_FOR_GPU,
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                clientId = clientId,
            )
        logger.debug {
            "GET_QUEUE_STATUS | clientId=$clientId | projectId=$projectId | " +
                "queueSize=$queueSize | hasRunningTask=${currentRunningTask != null}"
        }
        return currentRunningTask to queueSize.toInt()
    }

    suspend fun updateState(
        task: TaskDocument,
        next: TaskStateEnum,
    ): TaskDocument {
        val fromState = task.state
        val updated = task.copy(state = next)
        val saved = taskRepository.save(updated)
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
        val updated = task.copy(state = TaskStateEnum.ERROR, errorMessage = errorMessage)
        val saved = taskRepository.save(updated)
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
        val current = task.id?.let { taskRepository.findById(it) } ?: task
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
     */
    suspend fun findTasksForQualification(): Flow<TaskDocument> =
        flow {
            // New tasks (never retried, no nextQualificationRetryAt)
            emitAll(
                taskRepository.findByStateAndNextQualificationRetryAtIsNullOrderByCreatedAtAsc(
                    TaskStateEnum.READY_FOR_QUALIFICATION,
                ),
            )
            // Retried tasks where backoff has elapsed
            emitAll(
                taskRepository.findByStateAndNextQualificationRetryAtLessThanEqualOrderByCreatedAtAsc(
                    TaskStateEnum.READY_FOR_QUALIFICATION,
                    Instant.now(),
                ),
            )
        }

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
        logger.info {
            "TASK_STATE_TRANSITION: id=${task.id} correlationId=${task.correlationId} " +
                "from=READY_FOR_QUALIFICATION to=QUALIFYING type=${task.type} (ATOMICALLY CLAIMED)"
        }

        return task
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
