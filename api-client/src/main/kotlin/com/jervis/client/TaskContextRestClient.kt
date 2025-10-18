package com.jervis.client

import com.jervis.dto.CreateContextRequestDto
import com.jervis.dto.TaskContextDto
import com.jervis.service.ITaskContextService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class TaskContextRestClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : ITaskContextService {
    private val apiPath = "$baseUrl/api/task-contexts"

    override suspend fun create(requestDto: CreateContextRequestDto): TaskContextDto =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            }.body()

    override suspend fun save(context: TaskContextDto): TaskContextDto =
        httpClient
            .post("$apiPath/save") {
                contentType(ContentType.Application.Json)
                setBody(context)
            }.body()

    override suspend fun findById(contextId: String): TaskContextDto? = httpClient.get("$apiPath/$contextId").body()

    override suspend fun listForClient(clientId: String): List<TaskContextDto> = httpClient.get("$apiPath/client/$clientId").body()

    override suspend fun listForClientAndProject(
        clientId: String,
        projectId: String,
    ): List<TaskContextDto> =
        runCatching {
            httpClient.get("$apiPath/client/$clientId/project/$projectId").body<List<TaskContextDto>>()
        }.getOrElse { emptyList() }

    override suspend fun delete(contextId: String) {
        httpClient.delete("$apiPath/$contextId")
    }
}
