package com.jervis.client

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.service.ITaskQueryService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.bson.types.ObjectId

class TaskQueryRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : ITaskQueryService {
    private val apiPath = "$baseUrl/api/task-query"

    override suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument> =
        httpClient.get("$apiPath/project/${projectId.toHexString()}").body()

    override suspend fun getTasksByStatus(status: ScheduledTaskDocument.ScheduledTaskStatus): List<ScheduledTaskDocument> =
        httpClient.get("$apiPath/status/$status").body()
}
