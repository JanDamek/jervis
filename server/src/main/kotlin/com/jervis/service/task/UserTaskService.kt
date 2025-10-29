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
        val saved = userTaskRepository.save(document)

        logger.info { "Created user task: ${saved.id} - $title" }
        return saved.toDomain()
    }

    fun findActiveTasksByClient(clientId: ObjectId): Flow<UserTask> =
        userTaskRepository
            .findActiveTasksByClientIdAndStatusIn(
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
