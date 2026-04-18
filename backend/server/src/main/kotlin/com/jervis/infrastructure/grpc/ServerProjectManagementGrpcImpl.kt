package com.jervis.infrastructure.grpc

import com.jervis.client.ClientDocument
import com.jervis.client.ClientService
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.contracts.server.Alternative
import com.jervis.contracts.server.Client
import com.jervis.contracts.server.ClientList
import com.jervis.contracts.server.ConnectionSummary
import com.jervis.contracts.server.ConnectionSummaryList
import com.jervis.contracts.server.CreateClientRequest
import com.jervis.contracts.server.CreateClientResponse
import com.jervis.contracts.server.CreateConnectionRequest
import com.jervis.contracts.server.CreateConnectionResponse
import com.jervis.contracts.server.CreateProjectRequest
import com.jervis.contracts.server.CreateProjectResponse
import com.jervis.contracts.server.FeatureRecommendation
import com.jervis.contracts.server.GetStackRecommendationsRequest
import com.jervis.contracts.server.ListClientsRequest
import com.jervis.contracts.server.ListConnectionsRequest
import com.jervis.contracts.server.ListProjectsRequest
import com.jervis.contracts.server.PlatformRecommendation
import com.jervis.contracts.server.Project
import com.jervis.contracts.server.ProjectList
import com.jervis.contracts.server.ProjectRecommendations
import com.jervis.contracts.server.ServerProjectManagementServiceGrpcKt
import com.jervis.contracts.server.StackArchetype
import com.jervis.contracts.server.StorageRecommendation
import com.jervis.contracts.server.UpdateProjectRequest
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.project.ProjectDto
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectResource
import com.jervis.project.ProjectService
import com.jervis.project.ProjectTemplateService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import com.jervis.project.Alternative as DomainAlternative
import com.jervis.project.FeatureRecommendation as DomainFeatureRecommendation
import com.jervis.project.PlatformRecommendation as DomainPlatformRecommendation
import com.jervis.project.ProjectRecommendations as DomainProjectRecommendations
import com.jervis.project.StackArchetype as DomainStackArchetype
import com.jervis.project.StorageRecommendation as DomainStorageRecommendation

@Component
class ServerProjectManagementGrpcImpl(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val connectionService: ConnectionService,
    private val projectTemplateService: ProjectTemplateService,
) : ServerProjectManagementServiceGrpcKt.ServerProjectManagementServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    // ── Clients ──

    override suspend fun listClients(request: ListClientsRequest): ClientList {
        val all = clientService.list()
        val filtered = if (request.clientId.isNotBlank()) {
            all.filter { it.id.value.toHexString() == request.clientId }
        } else all
        return ClientList.newBuilder()
            .addAllItems(filtered.map { it.toClientProto() })
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

    override suspend fun listProjects(request: ListProjectsRequest): ProjectList {
        val projects = if (request.clientId.isNotBlank()) {
            projectService.listProjectsForClient(ClientId(ObjectId(request.clientId)))
        } else {
            projectService.getAllProjects()
        }
        return ProjectList.newBuilder()
            .addAllItems(projects.map { it.toProjectProto() })
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
            .setClientId(dto.clientId.orEmpty())
            .build()
    }

    override suspend fun updateProject(request: UpdateProjectRequest): Project {
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

        val saved = projectService.saveProject(updated)
        return saved.toProjectProto()
    }



    // ── Connections ──

    override suspend fun listConnections(request: ListConnectionsRequest): ConnectionSummaryList {
        val all = connectionService.findAll().toList()
        val filtered = if (request.clientId.isNotBlank()) {
            val client = clientService.getClientById(ClientId(ObjectId(request.clientId)))
            val allowed = client.connectionIds.toSet()
            all.filter { it.id.value in allowed }
        } else all
        return ConnectionSummaryList.newBuilder()
            .addAllItems(filtered.map { it.toSummaryProto() })
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
            .setId(saved.id.value.toHexString())
            .setName(saved.name)
            .setProvider(saved.provider.name)
            .setState(saved.state.name)
            .build()
    }

    // ── Project advisor ──

    override suspend fun getStackRecommendations(
        request: GetStackRecommendationsRequest,
    ): ProjectRecommendations =
        projectTemplateService.getRecommendations(request.requirements).toProto()

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

// ── DTO/document → proto converters ──────────────────────────────────────

private fun ClientDocument.toClientProto(): Client =
    Client.newBuilder()
        .setId(id.value.toHexString())
        .setName(name)
        .setDescription(description.orEmpty())
        .setArchived(archived)
        .setDefaultLanguage(defaultLanguageEnum.name)
        .build()

private fun ProjectDocument.toProjectProto(): Project =
    Project.newBuilder()
        .setId(id.value.toHexString())
        .setName(name)
        .setClientId(clientId.value.toHexString())
        .setGroupId(groupId?.value?.toHexString().orEmpty())
        .setDescription(description.orEmpty())
        .build()

private fun ProjectDto.toProjectProto(): Project =
    Project.newBuilder()
        .setId(id)
        .setName(name)
        .setClientId(clientId.orEmpty())
        .setGroupId(groupId.orEmpty())
        .setDescription(description.orEmpty())
        .build()

private fun ConnectionDocument.toSummaryProto(): ConnectionSummary =
    ConnectionSummary.newBuilder()
        .setId(id.value.toHexString())
        .setName(name)
        .setProvider(provider.name)
        .setState(state.name)
        .setBaseUrl(baseUrl)
        .addAllCapabilities(availableCapabilities.map { it.name })
        .build()

private fun DomainProjectRecommendations.toProto(): ProjectRecommendations =
    ProjectRecommendations.newBuilder()
        .setArchetype(archetype.toProto())
        .addAllPlatforms(platforms.map { it.toProto() })
        .addAllStorage(storage.map { it.toProto() })
        .addAllFeatures(features.map { it.toProto() })
        .setScaffoldingInstructions(scaffoldingInstructions)
        .build()

private fun DomainStackArchetype.toProto(): StackArchetype =
    StackArchetype.newBuilder()
        .setType(type)
        .setName(name)
        .setDescription(description)
        .addAllPros(pros)
        .addAllCons(cons)
        .setBestFor(bestFor)
        .build()

private fun DomainPlatformRecommendation.toProto(): PlatformRecommendation =
    PlatformRecommendation.newBuilder()
        .setPlatform(platform)
        .setRecommended(recommended)
        .setRationale(rationale)
        .addAllAlternatives(alternatives.map { it.toProto() })
        .build()

private fun DomainStorageRecommendation.toProto(): StorageRecommendation =
    StorageRecommendation.newBuilder()
        .setTechnology(technology)
        .setRecommended(recommended)
        .setUseCase(useCase)
        .setSpringDependency(springDependency)
        .addAllPros(pros)
        .addAllCons(cons)
        .build()

private fun DomainFeatureRecommendation.toProto(): FeatureRecommendation =
    FeatureRecommendation.newBuilder()
        .setFeature(feature)
        .setRecommended(recommended)
        .addAllOptions(options.map { it.toProto() })
        .build()

private fun DomainAlternative.toProto(): Alternative =
    Alternative.newBuilder()
        .setName(name)
        .setDescription(description)
        .build()
