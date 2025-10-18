package com.jervis.client

import com.jervis.dto.PlanDto
import com.jervis.dto.TaskContextDto
import com.jervis.service.ITaskQueryService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class TaskQueryRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : ITaskQueryService {
    private val apiPath = "$baseUrl/api/task-query"

    override suspend fun findContextById(contextId: String): TaskContextDto? = httpClient.get("$apiPath/contexts/$contextId").body()

    override suspend fun findPlanById(planId: String): PlanDto? = httpClient.get("$apiPath/plans/$planId").body()

    override suspend fun listContextsForClient(clientId: String): List<TaskContextDto> =
        httpClient.get("$apiPath/contexts/client/$clientId").body()

    override suspend fun listPlansForContext(contextId: String): List<PlanDto> = httpClient.get("$apiPath/plans/context/$contextId").body()

    override suspend fun listActivePlans(): List<PlanDto> = httpClient.get("$apiPath/plans/active").body()

    override suspend fun searchContexts(
        clientId: String?,
        projectId: String?,
        query: String?,
    ): List<TaskContextDto> =
        httpClient
            .get("$apiPath/contexts/search") {
                clientId?.let { url.parameters.append("clientId", it) }
                projectId?.let { url.parameters.append("projectId", it) }
                query?.let { url.parameters.append("query", it) }
            }.body()
}
