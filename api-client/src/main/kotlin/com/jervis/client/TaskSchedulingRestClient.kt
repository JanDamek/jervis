package com.jervis.client

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.service.ITaskSchedulingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.bson.types.ObjectId
import java.time.Instant

class TaskSchedulingRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : ITaskSchedulingService {
    private val apiPath = "$baseUrl/api/task-scheduling"

    override suspend fun scheduleTask(
        projectId: ObjectId,
        taskInstruction: String,
        taskName: String,
        scheduledAt: Instant,
        taskParameters: Map<String, String>,
        priority: Int,
        maxRetries: Int,
        cronExpression: String?,
        createdBy: String,
    ): ScheduledTaskDocument =
        httpClient
            .post("$apiPath/schedule") {
                contentType(ContentType.Application.Json)
                setBody(
                    ScheduleTaskRequest(
                        projectId = projectId,
                        taskInstruction = taskInstruction,
                        taskName = taskName,
                        scheduledAt = scheduledAt,
                        taskParameters = taskParameters,
                        priority = priority,
                        maxRetries = maxRetries,
                        cronExpression = cronExpression,
                        createdBy = createdBy,
                    ),
                )
            }.body()

    override suspend fun cancelTask(taskId: ObjectId): Boolean = httpClient.delete("$apiPath/cancel/${taskId.toHexString()}").body()

    private data class ScheduleTaskRequest(
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
