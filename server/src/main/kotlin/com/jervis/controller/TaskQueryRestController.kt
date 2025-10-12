package com.jervis.controller

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.service.ITaskQueryService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/task-query")
class TaskQueryRestController(
    private val taskQueryService: ITaskQueryService,
) {
    @GetMapping("/project/{projectId}")
    suspend fun getTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDocument> = taskQueryService.getTasksForProject(ObjectId(projectId))

    @GetMapping("/status/{status}")
    suspend fun getTasksByStatus(
        @PathVariable status: ScheduledTaskStatus,
    ): List<ScheduledTaskDocument> = taskQueryService.getTasksByStatus(status)
}
