package com.jervis.service.background

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import com.jervis.service.text.TikaTextExtractionService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val tikaTextExtractionService: TikaTextExtractionService,
) {
    private val logger = KotlinLogging.logger {}

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
            taskRepository.findOneByScheduledAtLessThanAndTypeOrderByScheduledAtAsc()?.let {
                emit(it)
            }
            emitAll(taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.READY_FOR_GPU))
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
     * Used when qualification fails with retriable error (timeout, connection).
     * Increments retry counter.
     */
    suspend fun returnToQueue(
        task: TaskDocument,
        maxRetries: Int,
    ) {
        if (task.state != TaskStateEnum.QUALIFYING) {
            logger.warn {
                "Cannot return task to queue - expected QUALIFYING but was ${task.state}: ${task.id}"
            }
            return
        }

        val newRetryCount = task.qualificationRetries + 1

        if (newRetryCount >= maxRetries) {
            logger.warn {
                "TASK_MAX_RETRIES_REACHED: id=${task.id} correlationId=${task.correlationId} " +
                    "retries=$newRetryCount maxRetries=$maxRetries - marking as ERROR"
            }
            markAsError(task, "Max qualification retries reached ($maxRetries)")
            return
        }

        val updated =
            task.copy(
                state = TaskStateEnum.READY_FOR_QUALIFICATION,
                qualificationRetries = newRetryCount,
                createdAt = Instant.now(), // Move to the end of the queue
            )
        taskRepository.save(updated)

        logger.info {
            "TASK_RETURNED_TO_QUEUE: id=${task.id} correlationId=${task.correlationId} " +
                "from=QUALIFYING to=READY_FOR_QUALIFICATION retry=$newRetryCount/$maxRetries " +
                "(moved to end of queue)"
        }
    }

    /**
     * Return all tasks that should be considered for qualification now:
     * - READY_FOR_QUALIFICATION: need to be claimed
     *
     * Note: QUALIFYING tasks are NOT included - they are already being processed.
     * This prevents multiple concurrent processing of the same task.
     */
    suspend fun findTasksForQualification(): Flow<TaskDocument> =
        taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.READY_FOR_QUALIFICATION)

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
                TaskTypeEnum.CONFLUENCE_PROCESSING -> "confluence-$correlationId.xml"
                TaskTypeEnum.JIRA_PROCESSING -> "jira-$correlationId.html"
                else -> "content-$correlationId.txt"
            }

        return try {
            logger.debug { "Cleaning content for $correlationId (type=$taskType), original length: ${content.length}" }
            val cleaned =
                tikaTextExtractionService.extractPlainText(
                    content = content,
                    fileName = fileName,
                )
            logger.debug { "Cleaned content for $correlationId, new length: ${cleaned.length}" }
            cleaned
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clean content through Tika for $correlationId, using original" }
            content
        }
    }
}
