package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.TaskId
import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.scheduling.TaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TaskSchedulingRpcImpl(
    private val taskSchedulingService: TaskSchedulingService,
) : ITaskSchedulingService {
    override suspend fun scheduleTask(
        clientId: String,
        projectId: String?,
        taskName: String,
        content: String,
        cronExpression: String?,
        correlationId: String?,
    ): ScheduledTaskDto =
        taskSchedulingService
            .scheduleTask(
                clientId = ClientId(ObjectId(clientId)),
                projectId = projectId?.let { ProjectId(ObjectId(it)) },
                content = content,
                taskName = taskName,
                scheduledAt = Instant.now(),
                cronExpression = cronExpression,
                correlationId = correlationId,
            ).toDto()

    override suspend fun findById(taskId: String): ScheduledTaskDto? = taskSchedulingService.findById(TaskId.fromString(taskId))?.toDto()

    override suspend fun listAllTasks(): List<ScheduledTaskDto> = taskSchedulingService.listAllTasks().map { it.toDto() }

    override suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto> =
        taskSchedulingService.listTasksForProject(ProjectId.fromString(projectId)).map { it.toDto() }

    override suspend fun listTasksForClient(clientId: String): List<ScheduledTaskDto> =
        taskSchedulingService.listTasksForClient(ClientId.fromString(clientId)).map { it.toDto() }

    override suspend fun cancelTask(taskId: String) {
        taskSchedulingService.cancelTask(TaskId.fromString(taskId))
    }
}
