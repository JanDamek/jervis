package com.jervis.infrastructure.grpc

import com.jervis.client.ClientDocument
import com.jervis.client.ClientService
import com.jervis.client.toDto
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.contracts.server.CreateClientRequest
import com.jervis.contracts.server.CreateClientResponse
import com.jervis.contracts.server.CreateConnectionRequest
import com.jervis.contracts.server.CreateConnectionResponse
import com.jervis.contracts.server.CreateProjectRequest
import com.jervis.contracts.server.CreateProjectResponse
import com.jervis.contracts.server.GetStackRecommendationsRequest
import com.jervis.contracts.server.GetStackRecommendationsResponse
import com.jervis.contracts.server.ListClientsRequest
import com.jervis.contracts.server.ListClientsResponse
import com.jervis.contracts.server.ListConnectionsRequest
import com.jervis.contracts.server.ListConnectionsResponse
import com.jervis.contracts.server.ListProjectsRequest
import com.jervis.contracts.server.ListProjectsResponse
import com.jervis.contracts.server.ServerProjectManagementServiceGrpcKt
import com.jervis.contracts.server.UpdateProjectRequest
import com.jervis.contracts.server.UpdateProjectResponse
import com.jervis.dto.client.ClientDto
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.project.ProjectDto
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectRecommendations
import com.jervis.project.ProjectResource
import com.jervis.project.ProjectService
import com.jervis.project.ProjectTemplateService
import com.jervis.project.toDto
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerProjectManagementGrpcImpl(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val connectionService: ConnectionService,
    private val projectTemplateService: ProjectTemplateService,
) : ServerProjectManagementServiceGrpcKt.ServerProjectManagementServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ── Clients ──

    override suspend fun listClients(request: ListClientsRequest): ListClientsResponse {
        val all = clientService.list()
        val filtered = if (request.clientId.isNotBlank()) {
            all.filter { it.id.value.toHexString() == request.clientId }
        } else all
        val dtos = filtered.map { it.toDto() }
        return ListClientsResponse.newBuilder()
            .setItemsJson(json.encodeToString(ListSerializer(ClientDto.serializer()), dtos))
            .build()
    }

    override suspend fun createClient(request: CreateClientRequest): CreateClientResponse {
        val doc = ClientDocument(
            name = request.name,
            description = request.description.takeIf { it.isNotBlank() },
        )
        val created = clientService.create(doc)
        logger.info { "CLIENT_CREATED | id=${created.id} name=${created.name}" }
        return CreateClientResponse.newBuilder()
            .setId(created.id.value.toHexString())
            .setName(created.name)
            .build()
    }

    // ── Projects ──

    override suspend fun listProjects(request: ListProjectsRequest): ListProjectsResponse {
        val projects = if (request.clientId.isNotBlank()) {
            projectService.listProjectsForClient(ClientId(ObjectId(request.clientId)))
        } else {
            projectService.getAllProjects()
        }
        val dtos = projects.map { it.toDto() }
        return ListProjectsResponse.newBuilder()
            .setItemsJson(json.encodeToString(ListSerializer(ProjectDto.serializer()), dtos))
            .build()
    }

    override suspend fun createProject(request: CreateProjectRequest): CreateProjectResponse {
        val clientIdValue = ClientId(ObjectId(request.clientId))
        clientService.getClientById(clientIdValue)
        val doc = ProjectDocument(
            clientId = clientIdValue,
            name = request.name,
            description = request.description.takeIf { it.isNotBlank() },
        )
        val dto = projectService.saveProject(doc)
        logger.info { "PROJECT_CREATED | id=${dto.id} client=${request.clientId} name=${dto.name}" }
        return CreateProjectResponse.newBuilder()
            .setId(dto.id)
            .setName(dto.name)
            .setClientId(dto.clientId)
            .build()
    }

    override suspend fun updateProject(request: UpdateProjectRequest): UpdateProjectResponse {
        val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(request.projectId)))
            ?: throw IllegalArgumentException("Project ${request.projectId} not found")

        var updated = project
        if (request.description.isNotBlank()) {
            updated = updated.copy(description = request.description)
        }
        if (request.gitRemoteUrl.isNotBlank()) {
            val resolvedConnectionId = resolveConnectionForGitUrl(
                gitUrl = request.gitRemoteUrl,
                explicitConnectionId = request.connectionId.takeIf { it.isNotBlank() },
                clientId = updated.clientId,
            )
            val resourceIdentifier = extractResourceIdentifier(request.gitRemoteUrl)
            val displayName = request.gitRemoteUrl.substringAfterLast("/").removeSuffix(".git")

            val existingResources = updated.resources.toMutableList()
            val repoIdx = existingResources.indexOfFirst {
                it.capability == ConnectionCapability.REPOSITORY
            }
            val newResource = ProjectResource(
                id = if (repoIdx >= 0) existingResources[repoIdx].id else ObjectId().toString(),
                connectionId = resolvedConnectionId,
                capability = ConnectionCapability.REPOSITORY,
                resourceIdentifier = resourceIdentifier,
                displayName = displayName,
            )
            if (repoIdx >= 0) existingResources[repoIdx] = newResource
            else existingResources.add(newResource)
            updated = updated.copy(
                resources = existingResources,
                workspaceStatus = null,
            )
        }

        val dto = projectService.saveProject(updated)
        return UpdateProjectResponse.newBuilder()
            .setId(dto.id)
            .setName(dto.name)
            .setBodyJson(json.encodeToString(ProjectDto.serializer(), dto))
            .build()
    }

    // ── Connections ──

    override suspend fun listConnections(request: ListConnectionsRequest): ListConnectionsResponse {
        val all = connectionService.findAll().toList()
        val filtered = if (request.clientId.isNotBlank()) {
            val client = clientService.getClientById(ClientId(ObjectId(request.clientId)))
            val allowed = client.connectionIds.toSet()
            all.filter { it.id.value in allowed }
        } else all
        val arr = buildJsonArray {
            for (conn in filtered) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(conn.id.toString()))
                    put("name", JsonPrimitive(conn.name))
                    put("provider", JsonPrimitive(conn.provider.name))
                    put("state", JsonPrimitive(conn.state.name))
                    put("baseUrl", JsonPrimitive(conn.baseUrl))
                    put("capabilities", buildJsonArray {
                        conn.availableCapabilities.forEach { add(JsonPrimitive(it.name)) }
                    })
                })
            }
        }
        return ListConnectionsResponse.newBuilder()
            .setItemsJson(arr.toString())
            .build()
    }

    override suspend fun createConnection(request: CreateConnectionRequest): CreateConnectionResponse {
        val provider = ProviderEnum.valueOf(request.provider)
        val protocol = ProtocolEnum.valueOf(request.protocol.ifBlank { "HTTP" })
        val authType = AuthTypeEnum.valueOf(request.authType.ifBlank { "BEARER" })
        val doc = ConnectionDocument(
            name = request.name,
            provider = provider,
            protocol = protocol,
            authType = authType,
            baseUrl = request.baseUrl,
            isCloud = request.isCloud,
            bearerToken = request.bearerToken.takeIf { it.isNotBlank() },
            username = request.username.takeIf { it.isNotBlank() },
            password = request.password.takeIf { it.isNotBlank() },
        )
        val saved = connectionService.save(doc)

        if (request.clientId.isNotBlank()) {
            val client = clientService.getClientById(ClientId(ObjectId(request.clientId)))
            val updatedIds = client.connectionIds + saved.id.value
            clientService.update(client.copy(connectionIds = updatedIds))
        }

        logger.info { "CONNECTION_CREATED | id=${saved.id} provider=${saved.provider} name=${saved.name}" }
        return CreateConnectionResponse.newBuilder()
            .setId(saved.id.toString())
            .setName(saved.name)
            .setProvider(saved.provider.name)
            .setState(saved.state.name)
            .build()
    }

    // ── Project advisor ──

    override suspend fun getStackRecommendations(
        request: GetStackRecommendationsRequest,
    ): GetStackRecommendationsResponse {
        val recommendations = projectTemplateService.getRecommendations(request.requirements)
        return GetStackRecommendationsResponse.newBuilder()
            .setBodyJson(json.encodeToString(ProjectRecommendations.serializer(), recommendations))
            .build()
    }

    // ── Helpers (local copy of legacy InternalProjectManagementRouting logic) ──

    private suspend fun resolveConnectionForGitUrl(
        gitUrl: String,
        explicitConnectionId: String?,
        clientId: ClientId,
    ): ObjectId {
        if (!explicitConnectionId.isNullOrBlank()) {
            return ObjectId(explicitConnectionId)
        }
        try {
            val client = clientService.getClientById(clientId)
            val providerForHost = detectProviderFromUrl(gitUrl) ?: return ObjectId()
            for (connOid in client.connectionIds) {
                val conn = connectionService.findById(ConnectionId(connOid)) ?: continue
                if (conn.provider == providerForHost &&
                    conn.availableCapabilities.any { it == ConnectionCapability.REPOSITORY }
                ) {
                    return conn.id.value
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "RESOLVE_CONNECTION | error for $gitUrl" }
        }
        return ObjectId()
    }

    private fun detectProviderFromUrl(gitUrl: String): ProviderEnum? {
        val lower = gitUrl.lowercase()
        return when {
            "github.com" in lower -> ProviderEnum.GITHUB
            "gitlab" in lower -> ProviderEnum.GITLAB
            "bitbucket" in lower -> ProviderEnum.ATLASSIAN
            else -> null
        }
    }

    private fun extractResourceIdentifier(gitUrl: String): String {
        val url = gitUrl.trim().removeSuffix(".git")
        if (url.startsWith("git@")) {
            val colonIdx = url.indexOf(':')
            if (colonIdx > 0) return url.substring(colonIdx + 1)
        }
        val knownHosts = listOf("github.com", "gitlab.com", "bitbucket.org", "dev.azure.com")
        for (host in knownHosts) {
            val idx = url.indexOf(host)
            if (idx >= 0) {
                val path = url.substring(idx + host.length).trimStart('/')
                return if (path.isNotEmpty()) path else url
            }
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val pathStart = url.indexOf('/', url.indexOf("://") + 3)
            if (pathStart > 0) return url.substring(pathStart + 1)
        }
        return url
    }

}
