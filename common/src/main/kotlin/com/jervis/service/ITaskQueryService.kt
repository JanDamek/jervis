package com.jervis.service

import com.jervis.dto.PlanDto
import com.jervis.dto.TaskContextDto

interface ITaskQueryService {
    suspend fun findContextById(contextId: String): TaskContextDto?

    suspend fun findPlanById(planId: String): PlanDto?

    suspend fun listContextsForClient(clientId: String): List<TaskContextDto>

    suspend fun listPlansForContext(contextId: String): List<PlanDto>

    suspend fun listActivePlans(): List<PlanDto>

    suspend fun searchContexts(
        clientId: String?,
        projectId: String?,
        query: String?,
    ): List<TaskContextDto>
}
