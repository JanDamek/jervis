package com.jervis.service.task

import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import com.jervis.service.notification.NotificationsPublisher
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class UserTaskService(
    private val userTaskRepository: TaskRepository,
    private val notificationsPublisher: NotificationsPublisher,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        title: String,
        description: String,
        projectId: ProjectId? = null,
        clientId: ClientId,
        correlationId: String,
        sourceUrn: SourceUrn = SourceUrn.unknownSource(),
    ): TaskDocument {
        val task =
            TaskDocument(
                taskName = title,
                content = description,
                projectId = projectId,
                clientId = clientId,
                correlationId = correlationId,
                type = TaskTypeEnum.USER_TASK,
                sourceUrn = sourceUrn,
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

    suspend fun getTaskById(taskId: TaskId): TaskDocument? = userTaskRepository.findById(taskId)

    suspend fun findActiveTasksByClient(clientId: ClientId): Flow<TaskDocument> =
        userTaskRepository
            .findByClientIdAndType(
                clientId = clientId,
                type = TaskTypeEnum.USER_TASK,
            )

    suspend fun cancelTask(taskId: TaskId): TaskDocument {
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

    suspend fun failAndEscalateToUserTask(
        task: TaskDocument,
        reason: String,
        error: Throwable? = null,
    ) {
        val title = "Background task failed: ${task.type}"
        val description =
            buildString {
                appendLine("Pending task ${task.id} failed in state ${task.state}")
                appendLine("Reason: $reason")
                error?.message?.let { appendLine("Error: $it") }
                appendLine()
                appendLine("Task Content:")
                appendLine(task.content)
            }
        createTask(
            title = title,
            description = description,
            projectId = task.projectId,
            clientId = task.clientId,
            correlationId = task.correlationId,
        )
        logger.info { "TASK_FAILED_ESCALATED:  reason=$reason" }
    }
}
