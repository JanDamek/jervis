package com.jervis.rpc

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.model.*
import ai.koog.a2a.transport.Request
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.rag.*
import com.jervis.dto.user.*
import com.jervis.service.IClientService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.IUserTaskService
import kotlinx.serialization.json.*

/**
 * A2ARPCClient - Client-side implementation of RPC services using Koog A2A.
 */
class A2ARPCClient(
    private val a2aClient: A2AClient,
    private val json: Json,
) {
    private fun generateId() = (0..1000000).random().toString()

    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonElement {
        val message =
            Message(
                messageId = generateId(),
                role = Role.User,
                parts = listOf(TextPart("RPC Call: $method")),
                contextId = "system-rpc",
                metadata =
                    buildJsonObject {
                        put("method", method)
                        params.forEach { (k, v) -> put(k, v) }
                    },
            )

        val response = a2aClient.sendMessage(Request(data = MessageSendParams(message)))
        val responseMessage = response.data as? Message ?: error("Expected message response")

        // Response data is in metadata
        return responseMessage.metadata?.get("result") ?: responseMessage.metadata ?: JsonNull
    }

    inner class ProjectServiceProxy : IProjectService {
        override suspend fun getAllProjects(): List<ProjectDto> {
            val result = call("project.list")
            return json.decodeFromJsonElement(result)
        }

        override suspend fun saveProject(project: ProjectDto): ProjectDto {
            val result =
                call(
                    "project.save",
                    buildJsonObject {
                        put("project", json.encodeToJsonElement(project))
                    },
                )
            return json.decodeFromJsonElement(result)
        }

        override suspend fun updateProject(
            id: String,
            project: ProjectDto,
        ): ProjectDto = saveProject(project.copy(id = id))

        override suspend fun deleteProject(project: ProjectDto) {
            call(
                "project.delete",
                buildJsonObject {
                    put("project", json.encodeToJsonElement(project))
                },
            )
        }

        override suspend fun getProjectByName(name: String?): ProjectDto {
            error("Not implemented via RPC yet")
        }
    }

    inner class ClientServiceProxy : IClientService {
        override suspend fun getAllClients(): List<ClientDto> {
            val result = call("client.list")
            return json.decodeFromJsonElement(result)
        }

        override suspend fun getClientById(id: String): ClientDto? {
            val result = call("client.get", buildJsonObject { put("id", id) })
            return json.decodeFromJsonElement(result)
        }

        override suspend fun createClient(client: ClientDto): ClientDto {
            val result =
                call(
                    "client.create",
                    buildJsonObject {
                        put("client", json.encodeToJsonElement(client))
                    },
                )
            return json.decodeFromJsonElement(result)
        }

        override suspend fun updateClient(
            id: String,
            client: ClientDto,
        ): ClientDto {
            val result =
                call(
                    "client.update",
                    buildJsonObject {
                        put("id", id)
                        put("client", json.encodeToJsonElement(client))
                    },
                )
            return json.decodeFromJsonElement(result)
        }

        override suspend fun deleteClient(id: String) {
            call("client.delete", buildJsonObject { put("id", id) })
        }

        override suspend fun updateLastSelectedProject(
            id: String,
            projectId: String?,
        ): ClientDto {
            val result =
                call(
                    "client.updateLastSelectedProject",
                    buildJsonObject {
                        put("id", id)
                        put("projectId", projectId)
                    },
                )
            return json.decodeFromJsonElement(result)
        }
    }

    inner class UserTaskServiceProxy : IUserTaskService {
        override suspend fun listActive(clientId: String): List<UserTaskDto> {
            val result = call("userTask.listActive", buildJsonObject { put("clientId", clientId) })
            return json.decodeFromJsonElement(result)
        }

        override suspend fun activeCount(clientId: String): UserTaskCountDto {
            val result = call("userTask.activeCount", buildJsonObject { put("clientId", clientId) })
            return json.decodeFromJsonElement(result)
        }

        override suspend fun cancel(taskId: String): UserTaskDto {
            val result = call("userTask.cancel", buildJsonObject { put("taskId", taskId) })
            return json.decodeFromJsonElement(result)
        }

        override suspend fun sendToAgent(
            taskId: String,
            routingMode: TaskRoutingMode,
            additionalInput: String?,
        ): UserTaskDto {
            val result =
                call(
                    "userTask.sendToAgent",
                    buildJsonObject {
                        put("taskId", taskId)
                        put("routingMode", routingMode.name)
                        put("additionalInput", additionalInput)
                    },
                )
            return json.decodeFromJsonElement(result)
        }
    }

    inner class RagSearchServiceProxy : IRagSearchService {
        override suspend fun search(request: RagSearchRequestDto): RagSearchResponseDto {
            val result =
                call(
                    "rag.search",
                    buildJsonObject {
                        put("request", json.encodeToJsonElement(request))
                    },
                )
            return json.decodeFromJsonElement(result)
        }
    }
}
