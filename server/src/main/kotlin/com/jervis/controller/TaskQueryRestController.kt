package com.jervis.controller

import com.jervis.dto.PlanDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.dto.TaskContextDto
import com.jervis.mapper.toDto
import com.jervis.service.scheduling.TaskQueryService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/task-query")
class TaskQueryRestController(
    private val taskQueryService: TaskQueryService,
) {
    @GetMapping("/context/{contextId}")
    suspend fun findContextById(
        @PathVariable contextId: String,
    ): TaskContextDto? = taskQueryService.findContextById(ObjectId(contextId))?.toDto()

    @GetMapping("/plan/{planId}")
    suspend fun findPlanById(
        @PathVariable planId: String,
    ): PlanDto? = taskQueryService.findPlanById(ObjectId(planId))?.toDto()

    @GetMapping("/contexts/client/{clientId}")
    suspend fun listContextsForClient(
        @PathVariable clientId: String,
    ): List<TaskContextDto> =
        taskQueryService
            .listContextsForClient(ObjectId(clientId))
            .map { it.toDto() }

    @GetMapping("/plans/context/{contextId}")
    suspend fun listPlansForContext(
        @PathVariable contextId: String,
    ): List<PlanDto> =
        taskQueryService
            .listPlansForContext(ObjectId(contextId))
            .map { it.toDto() }

    @GetMapping("/plans/active")
    suspend fun listActivePlans(): List<PlanDto> = taskQueryService.listActivePlans().map { it.toDto() }

    @GetMapping("/contexts/search")
    suspend fun searchContexts(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) query: String?,
    ): List<TaskContextDto> =
        taskQueryService
            .searchContexts(
                clientId?.let { ObjectId(it) },
                projectId?.let { ObjectId(it) },
                query,
            ).map { it.toDto() }

    @GetMapping("/tasks/project/{projectId}")
    suspend fun getTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDto> =
        taskQueryService
            .getTasksForProject(ObjectId(projectId))
            .map { it.toDto() }
}
