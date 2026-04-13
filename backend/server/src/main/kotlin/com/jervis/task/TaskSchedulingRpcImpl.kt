package com.jervis.task

import com.jervis.client.ClientRepository
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.TaskId
import com.jervis.dto.task.CalendarEntryDto
import com.jervis.dto.task.CalendarEntryType
import com.jervis.dto.task.ScheduledTaskDto
import com.jervis.dto.task.TaskStateEnum
import com.jervis.mapper.toDto
import com.jervis.project.ProjectRepository
import com.jervis.service.task.ITaskSchedulingService
import com.jervis.task.scheduling.TaskSchedulingService
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TaskSchedulingRpcImpl(
    private val taskSchedulingService: TaskSchedulingService,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
) : ITaskSchedulingService {
    override suspend fun scheduleTask(
        clientId: String,
        projectId: String?,
        taskName: String,
        content: String,
        scheduledAtEpochMs: Long?,
        cronExpression: String?,
        correlationId: String?,
    ): ScheduledTaskDto =
        taskSchedulingService
            .scheduleTask(
                clientId = ClientId(ObjectId(clientId)),
                projectId = projectId?.let { ProjectId(ObjectId(it)) },
                content = content,
                taskName = taskName,
                scheduledAt = scheduledAtEpochMs?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
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

    override suspend fun calendarEntries(
        fromEpochMs: Long,
        toEpochMs: Long,
        clientId: String?,
    ): List<CalendarEntryDto> {
        val from = Instant.ofEpochMilli(fromEpochMs)
        val to = Instant.ofEpochMilli(toEpochMs)
        val now = Instant.now()

        // Query: tasks with scheduledAt in range OR overdue (scheduledAt < now, state active)
        val criteria = Criteria().andOperator(
            Criteria.where("scheduledAt").ne(null),
            Criteria().orOperator(
                // In requested range
                Criteria.where("scheduledAt").gte(from).lte(to),
                // Overdue: before "from" but not DONE/ERROR
                Criteria().andOperator(
                    Criteria.where("scheduledAt").lt(from),
                    Criteria.where("state").nin(
                        TaskStateEnum.DONE.name,
                        TaskStateEnum.ERROR.name,
                    ),
                ),
            ),
        )
        if (clientId != null) {
            criteria.and("clientId").`is`(ObjectId(clientId))
        }

        val query = Query(criteria).limit(200)
        val tasks = kotlinx.coroutines.reactive.awaitSingle(
            mongoTemplate.find(query, TaskDocument::class.java).collectList(),
        )

        // Build client/project name caches
        val clientIds = tasks.mapNotNull { it.clientId }.distinct()
        val projectIds = tasks.mapNotNull { it.projectId }.distinct()
        val clientNames = clientIds.associateWith { cid ->
            try { clientRepository.findById(cid)?.name } catch (_: Exception) { null }
        }
        val projectNames = projectIds.associateWith { pid ->
            try { projectRepository.findById(pid)?.name } catch (_: Exception) { null }
        }

        return tasks.map { task ->
            val meeting = task.meetingMetadata
            val entryType = when {
                meeting != null -> CalendarEntryType.CALENDAR_EVENT
                task.type == com.jervis.dto.task.TaskTypeEnum.SCHEDULED -> CalendarEntryType.SCHEDULED_TASK
                else -> CalendarEntryType.DEADLINE_TASK
            }
            CalendarEntryDto(
                id = task.id.toString(),
                title = task.taskName,
                startEpochMs = (task.scheduledAt ?: task.createdAt).toEpochMilli(),
                endEpochMs = meeting?.endTime?.toEpochMilli(),
                entryType = entryType,
                state = task.state,
                clientId = task.clientId.toString(),
                projectId = task.projectId?.toString(),
                clientName = clientNames[task.clientId],
                projectName = projectNames[task.projectId],
                joinUrl = meeting?.joinUrl,
                meetingProvider = meeting?.provider?.name,
                cronExpression = task.cronExpression,
                isOverdue = task.scheduledAt != null && task.scheduledAt.isBefore(now)
                    && task.state !in setOf(TaskStateEnum.DONE, TaskStateEnum.ERROR),
                contentPreview = task.content.take(120),
            )
        }.sortedBy { it.startEpochMs }
    }
}
