package com.jervis.service.task

import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class UserTaskService(
    private val userTaskRepository: TaskRepository,
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
        return saved
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

    suspend fun findActiveTasksByClient(clientId: ClientId): List<TaskDocument> =
        userTaskRepository.findByClientIdAndType(clientId, TaskTypeEnum.USER_TASK).toList()

    fun cancelTask(fromString: TaskId): TaskDocument {
        TODO("Not yet implemented")
    }

    suspend fun getTaskById(fromString: TaskId) = userTaskRepository.findById(fromString)
}
