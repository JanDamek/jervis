package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.client.ClientDocument
import com.jervis.project.ProjectDocument
import com.jervis.connection.ConnectionDocument
import com.jervis.client.toDto
import com.jervis.project.toDto
import com.jervis.client.ClientService
import com.jervis.connection.ConnectionService
import com.jervis.project.ProjectService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
    projectTemplateService: com.jervis.project.ProjectTemplateService,
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
                pmJson.encodeToString(com.jervis.dto.client.ClientDto.serializer(), dto),
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
                        com.jervis.dto.client.ClientDto.serializer(),
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
                pmJson.encodeToString(com.jervis.dto.project.ProjectDto.serializer(), dto),
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
                        com.jervis.dto.project.ProjectDto.serializer(),
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

    // --- Update project ---
    put("/internal/projects/{id}") {
        try {
            val id = call.parameters["id"] ?: return@put call.respondText(
                """{"error":"Missing id"}""", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            val req = call.receive<UpdateProjectRequest>()
            val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(id)))
                ?: return@put call.respondText(
                    """{"error":"Project $id not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            // Apply partial updates
            var updated = project
            if (req.description != null) {
                updated = updated.copy(description = req.description)
            }
            if (req.gitRemoteUrl != null) {
                val resolvedConnectionId = resolveConnectionForGitUrl(
                    req.gitRemoteUrl, req.connectionId, updated.clientId, clientService, connectionService,
                )
                val resourceIdentifier = extractResourceIdentifier(req.gitRemoteUrl)
                val displayName = req.gitRemoteUrl.substringAfterLast("/").removeSuffix(".git")

                val existingResources = updated.resources.toMutableList()
                val repoIdx = existingResources.indexOfFirst {
                    it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                }
                val newResource = com.jervis.project.ProjectResource(
                    id = if (repoIdx >= 0) existingResources[repoIdx].id else ObjectId().toString(),
                    connectionId = resolvedConnectionId,
                    capability = com.jervis.dto.connection.ConnectionCapability.REPOSITORY,
                    resourceIdentifier = resourceIdentifier,
                    displayName = displayName,
                )
                if (repoIdx >= 0) {
                    existingResources[repoIdx] = newResource
                } else {
                    existingResources.add(newResource)
                }
                updated = updated.copy(
                    resources = existingResources,
                    workspaceStatus = null, // Reset to allow re-clone
                )
            }

            val dto = projectService.saveProject(updated)
            call.respondText(
                pmJson.encodeToString(com.jervis.dto.project.ProjectDto.serializer(), dto),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=PUT /internal/projects/{id}" }
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

            // Link connection to client if clientId provided
            if (req.clientId != null) {
                val client = clientService.getClientById(ClientId(ObjectId(req.clientId)))
                val updatedIds = client.connectionIds + saved.id.value
                clientService.update(client.copy(connectionIds = updatedIds))
            }

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
            val jsonArray = kotlinx.serialization.json.buildJsonArray {
                for (conn in connections) {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(conn.id.toString()))
                        put("name", kotlinx.serialization.json.JsonPrimitive(conn.name))
                        put("provider", kotlinx.serialization.json.JsonPrimitive(conn.provider.name))
                        put("state", kotlinx.serialization.json.JsonPrimitive(conn.state.name))
                        put("baseUrl", kotlinx.serialization.json.JsonPrimitive(conn.baseUrl))
                        put("capabilities", kotlinx.serialization.json.buildJsonArray {
                            conn.availableCapabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                        })
                    })
                }
            }
            call.respondText(jsonArray.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/connections" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Get stack recommendations (advisor pattern) ---
    post("/internal/project-advisor/recommendations") {
        try {
            val req = call.receive<GetRecommendationsRequest>()
            val recommendations = projectTemplateService.getRecommendations(req.requirements)
            call.respondText(
                pmJson.encodeToString(
                    com.jervis.project.ProjectRecommendations.serializer(),
                    recommendations,
                ),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/project-advisor/recommendations" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- List archetypes ---
    get("/internal/project-advisor/archetypes") {
        try {
            val archetypes = projectTemplateService.listArchetypes()
            call.respondText(
                pmJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.jervis.project.StackArchetype.serializer(),
                    ),
                    archetypes,
                ),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/project-advisor/archetypes" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

/**
 * Resolve the correct ConnectionDocument ObjectId for a git remote URL.
 *
 * Priority:
 * 1. Explicit connectionId parameter (caller knows which connection to use)
 * 2. Auto-detect from client's connections by matching git host to provider
 * 3. Fallback: random ObjectId (workspace clone will fail with clear error)
 */
private suspend fun resolveConnectionForGitUrl(
    gitUrl: String,
    explicitConnectionId: String?,
    clientId: ClientId,
    clientService: ClientService,
    connectionService: ConnectionService,
): ObjectId {
    // Priority 1: explicit connection ID
    if (!explicitConnectionId.isNullOrBlank()) {
        logger.info { "RESOLVE_CONNECTION | using explicit connectionId=$explicitConnectionId for $gitUrl" }
        return ObjectId(explicitConnectionId)
    }

    // Priority 2: auto-detect from client's connections
    try {
        val client = clientService.getClientById(clientId)
        val providerForHost = detectProviderFromUrl(gitUrl)
        if (providerForHost != null) {
            for (connOid in client.connectionIds) {
                val conn = connectionService.findById(com.jervis.common.types.ConnectionId(connOid)) ?: continue
                if (conn.provider == providerForHost &&
                    conn.availableCapabilities.any { it == com.jervis.dto.connection.ConnectionCapability.REPOSITORY }
                ) {
                    logger.info { "RESOLVE_CONNECTION | auto-detected connection '${conn.name}' (${conn.id}) for $gitUrl" }
                    return conn.id.value
                }
            }
        }
        logger.warn { "RESOLVE_CONNECTION | no matching connection found for $gitUrl (provider=$providerForHost, client=$clientId)" }
    } catch (e: Exception) {
        logger.warn(e) { "RESOLVE_CONNECTION | error resolving connection for $gitUrl" }
    }

    // Fallback: placeholder — workspace init will fail with a clear error
    return ObjectId()
}

/**
 * Detect git provider from URL hostname.
 */
private fun detectProviderFromUrl(gitUrl: String): com.jervis.dto.connection.ProviderEnum? {
    val lower = gitUrl.lowercase()
    return when {
        "github.com" in lower -> com.jervis.dto.connection.ProviderEnum.GITHUB
        "gitlab" in lower -> com.jervis.dto.connection.ProviderEnum.GITLAB
        "bitbucket" in lower -> com.jervis.dto.connection.ProviderEnum.ATLASSIAN
        else -> null
    }
}

/**
 * Extract org/repo identifier from a full git URL.
 *
 * Examples:
 * - "https://github.com/owner/repo.git" → "owner/repo"
 * - "https://gitlab.com/group/subgroup/project.git" → "group/subgroup/project"
 * - "git@github.com:owner/repo.git" → "owner/repo"
 * - "owner/repo" (already identifier) → "owner/repo"
 */
private fun extractResourceIdentifier(gitUrl: String): String {
    val url = gitUrl.trim().removeSuffix(".git")

    // SSH format: git@host:owner/repo
    if (url.startsWith("git@")) {
        val colonIdx = url.indexOf(':')
        if (colonIdx > 0) return url.substring(colonIdx + 1)
    }

    // HTTPS format: https://host/owner/repo
    val knownHosts = listOf("github.com", "gitlab.com", "bitbucket.org", "dev.azure.com")
    for (host in knownHosts) {
        val idx = url.indexOf(host)
        if (idx >= 0) {
            val path = url.substring(idx + host.length).trimStart('/')
            return if (path.isNotEmpty()) path else url
        }
    }

    // Generic HTTPS: strip protocol and host
    if (url.startsWith("http://") || url.startsWith("https://")) {
        val pathStart = url.indexOf('/', url.indexOf("://") + 3)
        if (pathStart > 0) return url.substring(pathStart + 1)
    }

    // Already an identifier (no protocol, no host)
    return url
}

@Serializable
data class GetRecommendationsRequest(
    val requirements: String,
)

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
data class UpdateProjectRequest(
    val description: String? = null,
    val gitRemoteUrl: String? = null,
    val connectionId: String? = null,
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
    val clientId: String? = null,
)
