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
        clientId: ObjectId? = null,
        needsQualification: Boolean = false,
        context: Map<String, String> = emptyMap(),
    ): PendingTask {
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

        logger.info { "Created pending task: ${saved.id} - ${taskType.name}, needsQualification=$needsQualification" }
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
}
