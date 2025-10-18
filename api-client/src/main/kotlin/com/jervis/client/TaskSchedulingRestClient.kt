package com.jervis.client

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.dto.ScheduledTaskDto
import com.jervis.service.ITaskSchedulingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class TaskSchedulingRestClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : ITaskSchedulingService {
    private val apiPath = "$baseUrl/api/task-scheduling"

    override suspend fun scheduleTask(
        projectId: String,
        taskName: String,
        taskInstruction: String,
        cronExpression: String?,
        priority: Int,
    ): ScheduledTaskDto =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "projectId" to projectId,
                        "taskName" to taskName,
                        "taskInstruction" to taskInstruction,
                        "cronExpression" to cronExpression,
                        "priority" to priority,
                    ),
                )
            }.body()

    override suspend fun findById(taskId: String): ScheduledTaskDto? = httpClient.get("$apiPath/$taskId").body()

    override suspend fun listAllTasks(): List<ScheduledTaskDto> = httpClient.get(apiPath).body()

    override suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto> =
        httpClient.get("$apiPath/project/$projectId").body()

    override suspend fun listPendingTasks(): List<ScheduledTaskDto> = httpClient.get("$apiPath/pending").body()

    override suspend fun cancelTask(taskId: String) {
        httpClient.delete("$apiPath/$taskId")
    }

    override suspend fun retryTask(taskId: String): ScheduledTaskDto = httpClient.post("$apiPath/$taskId/retry").body()

    override suspend fun updateTaskStatus(
        taskId: String,
        status: String,
        errorMessage: String?,
    ): ScheduledTaskDto =
        httpClient
            .put("$apiPath/$taskId/status") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "status" to status,
                        "errorMessage" to errorMessage,
                    ),
                )
            }.body()

    override fun getTasksByStatus(taskStatus: ScheduledTaskStatus): List<ScheduledTaskDto> {
        TODO("Not yet implemented")
    }
}
