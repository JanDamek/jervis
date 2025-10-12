package com.jervis.controller

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.service.ITaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/task-scheduling")
class TaskSchedulingRestController(
    private val taskSchedulingService: ITaskSchedulingService,
) {
    @PostMapping("/schedule")
    suspend fun scheduleTask(
        @RequestBody request: ScheduleTaskRequest,
    ): ScheduledTaskDocument =
        taskSchedulingService.scheduleTask(
            projectId = request.projectId,
            taskInstruction = request.taskInstruction,
            taskName = request.taskName,
            scheduledAt = request.scheduledAt,
            taskParameters = request.taskParameters,
            priority = request.priority,
            maxRetries = request.maxRetries,
            cronExpression = request.cronExpression,
            createdBy = request.createdBy,
        )

    @DeleteMapping("/cancel/{taskId}")
    suspend fun cancelTask(
        @PathVariable taskId: String,
    ): Boolean = taskSchedulingService.cancelTask(ObjectId(taskId))

    data class ScheduleTaskRequest(
        val projectId: ObjectId,
        val taskInstruction: String,
        val taskName: String,
        val scheduledAt: Instant,
        val taskParameters: Map<String, String> = emptyMap(),
        val priority: Int = 0,
        val maxRetries: Int = 3,
        val cronExpression: String? = null,
        val createdBy: String = "system",
    )
}
