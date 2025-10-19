package com.jervis.service

import com.jervis.dto.PlanDto
import com.jervis.dto.TaskContextDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

@HttpExchange("/api/task-query")
interface ITaskQueryService {
    @GetExchange("/contexts/{contextId}")
    suspend fun findContextById(
        @PathVariable contextId: String,
    ): TaskContextDto?

    @GetExchange("/plans/{planId}")
    suspend fun findPlanById(
        @PathVariable planId: String,
    ): PlanDto?

    @GetExchange("/contexts/client/{clientId}")
    suspend fun listContextsForClient(
        @PathVariable clientId: String): List<TaskContextDto>

    @GetExchange("/plans/context/{contextId}")
    suspend fun listPlansForContext(@PathVariable contextId: String): List<PlanDto>

    @GetExchange("/plans/active")
    suspend fun listActivePlans(): List<PlanDto>

    @GetExchange("/contexts/search")
    suspend fun searchContexts(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) query: String?,
    ): List<TaskContextDto>
}
