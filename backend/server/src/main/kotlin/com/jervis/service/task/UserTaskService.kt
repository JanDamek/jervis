package com.jervis.service.task

import com.jervis.entity.UserTaskDocument
import com.jervis.repository.UserTaskMongoRepository
import com.jervis.service.notification.NotificationsPublisher
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class UserTaskService(
    private val userTaskRepository: UserTaskMongoRepository,
    private val notificationsPublisher: NotificationsPublisher,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        title: String,
        description: String? = null,
        priority: TaskPriorityEnum = TaskPriorityEnum.MEDIUM,
        dueDate: Instant? = null,
        projectId: ObjectId? = null,
        clientId: ObjectId,
        sourceType: TaskSourceType,
        correlationId: String,
    ): UserTaskDocument {
        val existing =
            userTaskRepository.findFirstByClientIdAndSourceTypeAndStatusIn(
                clientId = clientId,
                sourceType = sourceType,
                statuses = listOf(TaskStatusEnum.TODO, TaskStatusEnum.IN_PROGRESS),
            )
        if (existing != null) {
            logger.info { "Skipped duplicate user task for (existing=${existing.id})" }
            return existing
        }

        val task =
            UserTaskDocument(
                title = title,
                description = description,
                priority = priority,
                dueDate = dueDate,
                projectId = projectId,
                clientId = clientId,
                sourceType = sourceType,
                correlationId = correlationId,
                status = TaskStatusEnum.TODO,
            )

        val saved = userTaskRepository.save(task)

        logger.info { "Created user task: ${saved.id} - $title" }
        notificationsPublisher.publishUserTaskCreated(
            clientId = clientId,
            task = saved,
            timestamp = Instant.now().toString(),
        )
        return saved
    }

    suspend fun getTaskById(taskId: ObjectId): UserTaskDocument? = userTaskRepository.findById(taskId)

    fun findActiveTasksByClient(clientId: ObjectId): Flow<UserTaskDocument> =
        userTaskRepository
            .findActiveTasksByClientIdAndStatusIn(
                clientId = clientId,
                statuses = listOf(TaskStatusEnum.TODO, TaskStatusEnum.IN_PROGRESS),
            )

    fun findTasksForToday(clientId: ObjectId): Flow<UserTaskDocument> {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return userTaskRepository
            .findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
                clientId = clientId,
                startDate = startOfDay,
                endDate = endOfDay,
                statuses = listOf(TaskStatusEnum.TODO, TaskStatusEnum.IN_PROGRESS),
            )
    }

    fun findTasksByDateRange(
        clientId: ObjectId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<UserTaskDocument> =
        userTaskRepository
            .findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
                clientId = clientId,
                startDate = startDate,
                endDate = endDate,
                statuses = listOf(TaskStatusEnum.TODO, TaskStatusEnum.IN_PROGRESS),
            )

    suspend fun cancelTask(taskId: ObjectId): UserTaskDocument {
        val existing = userTaskRepository.findById(taskId) ?: error("User task not found: $taskId")

        userTaskRepository.deleteById(taskId)
        logger.info { "Revoked user task deleted: ${existing.id}" }

        notificationsPublisher.publishUserTaskCancelled(
            clientId = existing.clientId,
            task = existing,
            timestamp = Instant.now().toString(),
        )

        return existing
    }
}
