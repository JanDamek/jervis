package com.jervis.client

import com.jervis.domain.context.TaskContext
import com.jervis.service.ITaskContextService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.bson.types.ObjectId

class TaskContextRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : ITaskContextService {
    private val apiPath = "$baseUrl/api/task-context"

    override suspend fun create(
        clientId: ObjectId,
        projectId: ObjectId,
        quick: Boolean,
        contextName: String,
    ): TaskContext =
        httpClient
            .post("$apiPath/create") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateContextRequest(
                        clientId = clientId,
                        projectId = projectId,
                        quick = quick,
                        contextName = contextName,
                    ),
                )
            }.body()

    override suspend fun save(context: TaskContext) {
        httpClient.post("$apiPath/save") {
            contentType(ContentType.Application.Json)
            setBody(context)
        }
    }

    override suspend fun findById(contextId: ObjectId): TaskContext? = httpClient.get("$apiPath/${contextId.toHexString()}").body()

    override suspend fun listFor(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): List<TaskContext> =
        httpClient
            .get("$apiPath/list") {
                parameter("clientId", clientId.toHexString())
                projectId?.let { parameter("projectId", it.toHexString()) }
            }.body()

    override suspend fun delete(contextId: ObjectId) {
        httpClient.delete("$apiPath/${contextId.toHexString()}")
    }

    @kotlinx.serialization.Serializable
    private data class CreateContextRequest(
        @kotlinx.serialization.Serializable(with = com.jervis.serialization.ObjectIdSerializer::class)
        val clientId: ObjectId,
        @kotlinx.serialization.Serializable(with = com.jervis.serialization.ObjectIdSerializer::class)
        val projectId: ObjectId,
        val quick: Boolean = false,
        val contextName: String = "New Context",
    )
}
