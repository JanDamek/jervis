package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.mapper.toDto
import com.jervis.service.client.ClientService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.project.ProjectService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}

private val pmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Internal REST endpoints for project management operations.
 *
 * Called by MCP tools and Python orchestrator for creating clients,
 * projects, and connections during automated workflows (SETUP vertex type).
 *
 * Endpoints:
 * - POST /internal/clients          — create a new client
 * - GET  /internal/clients          — list all clients
 * - POST /internal/projects         — create a new project for a client
 * - GET  /internal/projects         — list projects (optionally by clientId)
 * - POST /internal/connections      — create a new connection
 * - GET  /internal/connections      — list all connections
 */
fun Routing.installInternalProjectManagementApi(
    clientService: ClientService,
    projectService: ProjectService,
    connectionService: ConnectionService,
) {
    // --- Create client ---
    post("/internal/clients") {
        try {
            val req = call.receive<CreateClientRequest>()
            val doc = ClientDocument(
                name = req.name,
                description = req.description,
            )
            val created = clientService.create(doc)
            val dto = created.toDto()
            call.respondText(
                pmJson.encodeToString(com.jervis.dto.ClientDto.serializer(), dto),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/clients" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- List clients ---
    get("/internal/clients") {
        try {
            val clients = clientService.list()
            val dtos = clients.map { it.toDto() }
            call.respondText(
                pmJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.jervis.dto.ClientDto.serializer(),
                    ),
                    dtos,
                ),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/clients" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Create project ---
    post("/internal/projects") {
        try {
            val req = call.receive<CreateProjectRequest>()
            // Verify client exists
            clientService.getClientById(ClientId(ObjectId(req.clientId)))

            val doc = ProjectDocument(
                clientId = ClientId(ObjectId(req.clientId)),
                name = req.name,
                description = req.description,
            )
            val dto = projectService.saveProject(doc)
            call.respondText(
                pmJson.encodeToString(com.jervis.dto.ProjectDto.serializer(), dto),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        } catch (e: IllegalArgumentException) {
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/projects" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- List projects (optionally by clientId) ---
    get("/internal/projects") {
        try {
            val clientId = call.request.queryParameters["clientId"]
            val projects = if (clientId != null) {
                projectService.listProjectsForClient(ClientId(ObjectId(clientId)))
            } else {
                projectService.getAllProjects()
            }
            val dtos = projects.map { it.toDto() }
            call.respondText(
                pmJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.jervis.dto.ProjectDto.serializer(),
                    ),
                    dtos,
                ),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/projects" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Create connection ---
    post("/internal/connections") {
        try {
            val req = call.receive<CreateConnectionRequest>()
            val provider = com.jervis.dto.connection.ProviderEnum.valueOf(req.provider)
            val protocol = com.jervis.dto.connection.ProtocolEnum.valueOf(req.protocol)
            val authType = com.jervis.dto.connection.AuthTypeEnum.valueOf(req.authType)

            val doc = ConnectionDocument(
                name = req.name,
                provider = provider,
                protocol = protocol,
                authType = authType,
                baseUrl = req.baseUrl ?: "",
                isCloud = req.isCloud,
                bearerToken = req.bearerToken,
                username = req.username,
                password = req.password,
            )
            val saved = connectionService.save(doc)
            call.respondText(
                """{"id":"${saved.id}","name":"${saved.name}","provider":"${saved.provider}","state":"${saved.state}"}""",
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        } catch (e: IllegalArgumentException) {
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/connections" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- List connections ---
    get("/internal/connections") {
        try {
            val connections = mutableListOf<ConnectionDocument>()
            connectionService.findAll().collect { connections.add(it) }
            val result = connections.map { conn ->
                mapOf(
                    "id" to conn.id.toString(),
                    "name" to conn.name,
                    "provider" to conn.provider.name,
                    "state" to conn.state.name,
                    "baseUrl" to conn.baseUrl,
                    "capabilities" to conn.availableCapabilities.map { it.name },
                )
            }
            call.respondText(
                pmJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        kotlinx.serialization.builtins.MapSerializer(
                            kotlinx.serialization.builtins.serializer<String>(),
                            kotlinx.serialization.json.JsonElement.serializer(),
                        ),
                    ),
                    result.map { entry ->
                        entry.mapValues { (_, v) ->
                            when (v) {
                                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                                is List<*> -> kotlinx.serialization.json.JsonArray(
                                    v.map { kotlinx.serialization.json.JsonPrimitive(it as String) },
                                )
                                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                            }
                        },
                    ),
                ),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/connections" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class CreateClientRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class CreateProjectRequest(
    val clientId: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class CreateConnectionRequest(
    val name: String,
    val provider: String,
    val protocol: String = "HTTP",
    val authType: String = "BEARER",
    val baseUrl: String? = null,
    val isCloud: Boolean = false,
    val bearerToken: String? = null,
    val username: String? = null,
    val password: String? = null,
)
