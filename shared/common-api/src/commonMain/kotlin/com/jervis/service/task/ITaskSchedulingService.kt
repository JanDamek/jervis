package com.jervis.service.task

import com.jervis.dto.task.CalendarEntryDto
import com.jervis.dto.task.ScheduledTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ITaskSchedulingService {
    suspend fun scheduleTask(
        clientId: String,
        projectId: String?,
        taskName: String,
        content: String,
        scheduledAtEpochMs: Long?,
        cronExpression: String?,
        correlationId: String?,
    ): ScheduledTaskDto

    suspend fun findById(taskId: String): ScheduledTaskDto?

    suspend fun listAllTasks(): List<ScheduledTaskDto>

    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto>

    suspend fun listTasksForClient(clientId: String): List<ScheduledTaskDto>

    suspend fun cancelTask(taskId: String)

    /**
     * Calendar view: all entries (scheduled tasks, calendar events, deadline tasks)
     * within the given date range. Tasks without a deadline are treated as "today".
     * Overdue tasks (scheduledAt < now, state != DONE) are included with isOverdue=true.
     */
    suspend fun calendarEntries(
        fromEpochMs: Long,
        toEpochMs: Long,
        clientId: String? = null,
    ): List<CalendarEntryDto>
}
