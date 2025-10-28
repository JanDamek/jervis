package com.jervis.service.task

import com.jervis.domain.task.TaskPriority
import com.jervis.domain.task.TaskSourceType
import com.jervis.domain.task.TaskStatus
import com.jervis.domain.task.UserTask
import com.jervis.entity.UserTaskDocument
import com.jervis.repository.mongo.UserTaskMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class UserTaskService(
    private val userTaskRepository: UserTaskMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        title: String,
        description: String? = null,
        priority: TaskPriority = TaskPriority.MEDIUM,
        dueDate: Instant? = null,
        projectId: ObjectId? = null,
        clientId: ObjectId,
        sourceType: TaskSourceType,
        sourceUri: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): UserTask {
        val task =
            UserTask(
                title = title,
                description = description,
                priority = priority,
                dueDate = dueDate,
                projectId = projectId,
                clientId = clientId,
                sourceType = sourceType,
                sourceUri = sourceUri,
                metadata = metadata,
            )

        val document = UserTaskDocument.fromDomain(task)
        val saved = userTaskRepository.save(document).block()!!

        logger.info { "Created user task: ${saved.id} - $title" }
        return saved.toDomain()
    }

    suspend fun updateTaskStatus(
        taskId: ObjectId,
        newStatus: TaskStatus,
    ): UserTask? {
        val document = userTaskRepository.findById(taskId).block() ?: return null

        val updated =
            document.copy(
                status = newStatus.name,
                completedAt = if (newStatus == TaskStatus.COMPLETED) Instant.now() else null,
            )

        val saved = userTaskRepository.save(updated).block()!!
        logger.info { "Updated task $taskId status to $newStatus" }
        return saved.toDomain()
    }

    suspend fun deleteTask(taskId: ObjectId) {
        userTaskRepository.deleteById(taskId)
        logger.info { "Deleted user task: $taskId" }
    }

    fun findActiveTasksByClient(clientId: ObjectId): Flow<UserTask> =
        userTaskRepository
            .findActiveTasksByClient(
                clientId = clientId,
                statuses = listOf(TaskStatus.TODO.name, TaskStatus.IN_PROGRESS.name),
            ).map { it.toDomain() }

    fun findTasksForToday(clientId: ObjectId): Flow<UserTask> {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return userTaskRepository
            .findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
                clientId = clientId,
                startDate = startOfDay,
                endDate = endOfDay,
                statuses = listOf(TaskStatus.TODO.name, TaskStatus.IN_PROGRESS.name),
            ).map { it.toDomain() }
    }

    fun findTasksByDateRange(
        clientId: ObjectId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<UserTask> =
        userTaskRepository
            .findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
                clientId = clientId,
                startDate = startDate,
                endDate = endDate,
                statuses = listOf(TaskStatus.TODO.name, TaskStatus.IN_PROGRESS.name),
            ).map { it.toDomain() }
}
