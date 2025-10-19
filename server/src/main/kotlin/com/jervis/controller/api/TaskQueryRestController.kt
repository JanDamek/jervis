package com.jervis.controller.api

import com.jervis.dto.PlanDto
import com.jervis.dto.TaskContextDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskQueryService
import com.jervis.service.scheduling.TaskQueryService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TaskQueryRestController(
    private val taskQueryService: TaskQueryService,
) : ITaskQueryService {
    override suspend fun findContextById(
        @PathVariable contextId: String,
    ): TaskContextDto? = taskQueryService.findContextById(ObjectId(contextId))?.toDto()

    override suspend fun findPlanById(
        @PathVariable planId: String,
    ): PlanDto? = taskQueryService.findPlanById(ObjectId(planId))?.toDto()

    override suspend fun listContextsForClient(
        @PathVariable clientId: String,
    ): List<TaskContextDto> =
        taskQueryService
            .listContextsForClient(ObjectId(clientId))
            .map { it.toDto() }

    override suspend fun listPlansForContext(
        @PathVariable contextId: String,
    ): List<PlanDto> =
        taskQueryService
            .listPlansForContext(ObjectId(contextId))
            .map { it.toDto() }

    override suspend fun listActivePlans(): List<PlanDto> = taskQueryService.listActivePlans().map { it.toDto() }

    override suspend fun searchContexts(
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
}
