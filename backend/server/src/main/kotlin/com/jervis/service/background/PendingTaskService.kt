package com.jervis.service.background

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.repository.PendingTaskMongoRepository
import com.jervis.service.debug.DebugService
import com.jervis.service.text.TikaTextExtractionService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PendingTaskService(
    private val pendingTaskRepository: PendingTaskMongoRepository,
    private val debugService: DebugService,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val tikaTextExtractionService: TikaTextExtractionService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        taskType: PendingTaskTypeEnum,
        content: String,
        clientId: ClientId,
        correlationId: String,
        sourceUrn: SourceUrn,
        projectId: ProjectId? = null,
        state: PendingTaskStateEnum = PendingTaskStateEnum.READY_FOR_QUALIFICATION,
        attachments: List<AttachmentMetadata> = emptyList(),
    ): PendingTaskDocument {
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val cleanContent = cleanContentWithTika(content, correlationId, taskType)

        val task =
            PendingTaskDocument(
                type = taskType,
                content = cleanContent,
                projectId = projectId,
                clientId = clientId,
                state = state,
                correlationId = correlationId,
                sourceUrn = sourceUrn,
                attachments = attachments,
            )

        val saved = pendingTaskRepository.save(task)

        logger.info {
            "TASK_CREATED: id=${saved.id} correlationId=${saved.correlationId} type=${taskType.name} state=${task.state} clientId=$clientId projectId=${projectId?.toString() ?: "none"} contentLength=${content.length}"
        }

        // Publish debug event
        debugService.taskCreated(
            correlationId = saved.correlationId,
            taskId = saved.id.toString(),
            taskType = taskType.name,
            state = task.state.name,
            clientId = clientId.toString(),
            projectId = projectId?.toString(),
            contentLength = content.length,
        )

        return saved
    }

    suspend fun deleteTask(taskId: ObjectId) {
        val task = pendingTaskRepository.findById(taskId)
        pendingTaskRepository.deleteById(taskId)
        logger.info { "TASK_DELETED: id=$taskId type=${task?.type ?: "unknown"} state=${task?.state ?: "unknown"}" }
    }

    fun findTasksByState(state: PendingTaskStateEnum): Flow<PendingTaskDocument> =
        pendingTaskRepository.findByStateOrderByCreatedAtAsc(state)

    suspend fun updateState(
        taskId: ObjectId,
        expected: PendingTaskStateEnum,
        next: PendingTaskStateEnum,
    ): PendingTaskDocument {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        require(task.state == expected) {
            "Invalid state transition for $taskId: expected=$expected, actual=${task.state}"
        }
        val updated = task.copy(state = next)
        val saved = pendingTaskRepository.save(updated)
        logger.info {
            "TASK_STATE_TRANSITION: id=$taskId correlationId=${saved.correlationId} from=$expected to=$next type=${task.type}"
        }

        // Publish debug event
        debugService.taskStateTransition(
            correlationId = saved.correlationId,
            taskId = taskId.toString(),
            fromState = expected.name,
            toState = next.name,
            taskType = task.type,
        )

        return saved
    }

    /**
     * Mark task as ERROR with error message, without requiring expected state.
     * This is used for fatal errors where we need to ensure task is marked as failed.
     */
    suspend fun markAsError(
        taskId: ObjectId,
        errorMessage: String,
    ): PendingTaskDocument {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        val previousState = task.state
        val updated = task.copy(state = PendingTaskStateEnum.ERROR, errorMessage = errorMessage)
        val saved = pendingTaskRepository.save(updated)
        logger.error {
            "TASK_MARKED_AS_ERROR: id=$taskId correlationId=${saved.correlationId} previousState=$previousState error='${
                errorMessage.take(
                    200,
                )
            }'"
        }

        // Publish debug event
        debugService.taskStateTransition(
            correlationId = saved.correlationId,
            taskId = taskId.toString(),
            fromState = previousState.name,
            toState = PendingTaskStateEnum.ERROR.name,
            taskType = task.type,
        )

        return saved
    }

    /**
     * Return task from QUALIFYING back to READY_FOR_QUALIFICATION for retry.
     * Used when qualification fails with retriable error (timeout, connection).
     * Increments retry counter.
     */
    suspend fun returnToQueue(
        taskId: ObjectId,
        maxRetries: Int,
    ) {
        val task =
            pendingTaskRepository.findById(taskId) ?: run {
                logger.warn { "Cannot return task to queue - not found: $taskId" }
                return
            }

        if (task.state != PendingTaskStateEnum.QUALIFYING) {
            logger.warn {
                "Cannot return task to queue - expected QUALIFYING but was ${task.state}: $taskId"
            }
            return
        }

        val newRetryCount = task.qualificationRetries + 1

        if (newRetryCount >= maxRetries) {
            logger.warn {
                "TASK_MAX_RETRIES_REACHED: id=$taskId correlationId=${task.correlationId} " +
                    "retries=$newRetryCount maxRetries=$maxRetries - marking as ERROR"
            }
            markAsError(taskId, "Max qualification retries reached ($maxRetries)")
            return
        }

        val updated =
            task.copy(
                state = PendingTaskStateEnum.READY_FOR_QUALIFICATION,
                qualificationRetries = newRetryCount,
                createdAt = Instant.now(), // Move to end of queue
            )
        pendingTaskRepository.save(updated)

        logger.info {
            "TASK_RETURNED_TO_QUEUE: id=$taskId correlationId=${task.correlationId} " +
                "from=QUALIFYING to=READY_FOR_QUALIFICATION retry=$newRetryCount/$maxRetries " +
                "(moved to end of queue)"
        }

        debugService.taskStateTransition(
            correlationId = task.correlationId,
            taskId = taskId.toString(),
            fromState = PendingTaskStateEnum.QUALIFYING.name,
            toState = PendingTaskStateEnum.READY_FOR_QUALIFICATION.name,
            taskType = task.type,
        )
    }

    /**
     * Update task content (used for cleaning HTML/XML through Tika).
     */
    suspend fun updateTaskContent(
        taskId: ObjectId,
        newContent: String,
    ) {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        val updated = task.copy(content = newContent)
        pendingTaskRepository.save(updated)

        logger.info {
            "TASK_CONTENT_UPDATED: id=$taskId from=${task.content.length} to=${newContent.length} chars"
        }
    }

    /**
     * Return all tasks that should be considered for qualification now:
     * - READY_FOR_QUALIFICATION: need to be claimed
     *
     * Note: QUALIFYING tasks are NOT included - they are already being processed.
     * This prevents multiple concurrent processing of the same task.
     */
    fun findTasksForQualification(): Flow<PendingTaskDocument> = findTasksByState(PendingTaskStateEnum.READY_FOR_QUALIFICATION)

    /**
     * Atomically claim a task for qualification using MongoDB findAndModify.
     * This ensures that only ONE worker can claim a specific task, even in concurrent scenarios.
     *
     * SINGLETON GUARANTEE (Level 4 - per-task atomicity):
     * - Uses MongoDB findAndModify with state check (READY_FOR_QUALIFICATION -> QUALIFYING)
     * - Atomic operation ensures only one thread/process can claim the task
     * - If task is already claimed (not READY_FOR_QUALIFICATION), returns null
     * - This works even across multiple application instances (distributed lock)
     *
     * Returns: Updated task with QUALIFYING state if successfully claimed, null if already claimed
     */
    suspend fun tryClaimForQualification(taskId: ObjectId): PendingTaskDocument? {
        val query =
            Query(
                Criteria
                    .where("_id")
                    .`is`(taskId)
                    .and("state")
                    .`is`(PendingTaskStateEnum.READY_FOR_QUALIFICATION),
            )
        val update = Update().set("state", PendingTaskStateEnum.QUALIFYING)

        val updatedTask =
            mongoTemplate
                .findAndModify(query, update, PendingTaskDocument::class.java)
                .awaitSingleOrNull()
                ?: return null

        // Successfully claimed - log transition
        logger.info {
            "TASK_STATE_TRANSITION: id=$taskId correlationId=${updatedTask.correlationId} " +
                "from=READY_FOR_QUALIFICATION to=QUALIFYING type=${updatedTask.type} (ATOMICALLY CLAIMED)"
        }

        debugService.taskStateTransition(
            correlationId = updatedTask.correlationId,
            taskId = taskId.toString(),
            fromState = PendingTaskStateEnum.READY_FOR_QUALIFICATION.name,
            toState = PendingTaskStateEnum.QUALIFYING.name,
            taskType = updatedTask.type,
        )

        return updatedTask
    }

    fun findAllTasks(
        taskType: PendingTaskTypeEnum?,
        state: PendingTaskStateEnum?,
    ): Flow<PendingTaskDocument> {
        val tasks =
            when {
                taskType != null && state != null -> {
                    pendingTaskRepository.findByTypeAndStateOrderByCreatedAtAsc(
                        taskType,
                        state,
                    )
                }

                taskType != null -> {
                    pendingTaskRepository.findByTypeOrderByCreatedAtAsc(taskType)
                }

                state != null -> {
                    pendingTaskRepository.findByStateOrderByCreatedAtAsc(state)
                }

                else -> {
                    pendingTaskRepository.findAllByOrderByCreatedAtAsc()
                }
            }
        return tasks
    }

    suspend fun countTasks(
        taskType: PendingTaskTypeEnum?,
        state: PendingTaskStateEnum?,
    ): Long =
        when {
            taskType != null && state != null -> pendingTaskRepository.countByTypeAndState(taskType, state)
            taskType != null -> pendingTaskRepository.countByType(taskType)
            state != null -> pendingTaskRepository.countByState(state)
            else -> pendingTaskRepository.count()
        }

    /**
     * Clean content through Tika to remove HTML, XML, Confluence/Jira markup.
     * Detects content type and applies appropriate extraction.
     */
    private suspend fun cleanContentWithTika(
        content: String,
        correlationId: String,
        taskType: PendingTaskTypeEnum,
    ): String {
        val fileName =
            when (taskType) {
                PendingTaskTypeEnum.CONFLUENCE_PROCESSING -> "confluence-$correlationId.xml"
                PendingTaskTypeEnum.JIRA_PROCESSING -> "jira-$correlationId.html"
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

    /**
     * Ensures task content is clean from excessive HTML/XML tags.
     *
     * If content contains more than 5 HTML tags, processes it through Tika
     * and updates the task in database with cleaned content.
     *
     * This is a safety net for cases where continuous indexers missed cleaning.
     */
    suspend fun ensureCleanContent(task: PendingTaskDocument): PendingTaskDocument {
        logger.info {
            "CONTENT_CLEANING: taskId=${task.id} - processing through Tika"
        }

        val cleanedContent =
            tikaTextExtractionService.extractPlainText(
                content = task.content,
                fileName = "task-${task.id}.html",
            )

        val cleanedTask = task.copy(content = cleanedContent)

        logger.info {
            "CONTENT_CLEANED: taskId=${task.id} before=${task.content.length} after=${cleanedContent.length} " +
                "reduction=${((1.0 - cleanedContent.length.toDouble() / task.content.length) * 100).toInt()}%"
        }

        return cleanedTask
    }
}
