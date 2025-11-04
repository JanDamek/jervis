package com.jervis.service.background

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.repository.mongo.PendingTaskMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class PendingTaskService(
    private val pendingTaskRepository: PendingTaskMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        taskType: PendingTaskTypeEnum,
        content: String? = null,
        projectId: ObjectId? = null,
        clientId: ObjectId,
        needsQualification: Boolean = false,
        context: Map<String, String> = emptyMap(),
    ): PendingTask {
        // Idempotency: for EMAIL_PROCESSING with sourceUri, do not create duplicates
        if (taskType == PendingTaskTypeEnum.EMAIL_PROCESSING) {
            val sourceUri = context["sourceUri"]
            if (!sourceUri.isNullOrBlank()) {
                val existing =
                    pendingTaskRepository.findFirstByClientAndTypeAndSourceUri(clientId, taskType.name, sourceUri)
                if (existing != null) {
                    logger.info { "Reusing existing pending task ${existing.id} for EMAIL_PROCESSING sourceUri=$sourceUri" }
                    return existing.toDomain()
                }
            }
        }

        val task =
            PendingTask(
                taskType = taskType,
                content = content,
                projectId = projectId,
                clientId = clientId,
                needsQualification = needsQualification,
                context = context,
            )

        val document = PendingTaskDocument.fromDomain(task)
        val saved = pendingTaskRepository.save(document)

        logger.info { "Created pending task: ${'$'}{saved.id} - ${'$'}{taskType.name}, needsQualification=${'$'}needsQualification" }
        return saved.toDomain()
    }

    suspend fun deleteTask(taskId: ObjectId) {
        pendingTaskRepository.deleteById(taskId)
        logger.info { "Deleted pending task: $taskId" }
    }

    fun findTasksNeedingQualification(needsQualification: Boolean = true): Flow<PendingTask> =
        pendingTaskRepository
            .findByNeedsQualificationOrderByCreatedAtAsc(needsQualification)
            .map { it.toDomain() }

    suspend fun setNeedsQualification(
        taskId: ObjectId,
        needsQualification: Boolean,
    ) {
        val task = pendingTaskRepository.findById(taskId) ?: return
        val updated = task.copy(needsQualification = needsQualification)
        pendingTaskRepository.save(updated)
        logger.debug { "Updated task $taskId needsQualification=$needsQualification" }
    }

    /**
     * Merge additional context into existing task.
     * Validates that all values are non-blank (fail fast on blank values).
     *
     * @param taskId Task ID to update
     * @param contextPatch Map of context keys to merge (values must be non-blank)
     * @return Updated task
     * @throws IllegalArgumentException if task not found or any value is blank
     */
    suspend fun mergeContext(
        taskId: ObjectId,
        contextPatch: Map<String, String>,
    ): PendingTask {
        val task =
            pendingTaskRepository.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")

        // Fail fast: reject blank values
        contextPatch.forEach { (key, value) ->
            require(value.isNotBlank()) { "Context key '$key' has blank value for task $taskId" }
        }

        val merged = task.copy(context = task.context + contextPatch)
        val saved = pendingTaskRepository.save(merged)

        logger.info {
            "TASK_CONTEXT_MERGE: Task ${taskId.toHexString()} merged keys ${contextPatch.keys.joinToString(", ")}"
        }

        return saved.toDomain()
    }
}
