package com.jervis.rpc

import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.connection.RateLimitConfigDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.IConnectionService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.oauth2.OAuth2Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ConnectionRpcImpl(
    private val connectionService: ConnectionService,
    private val providerRegistry: ProviderRegistry,
    private val oauth2Service: OAuth2Service,
) : IConnectionService {
    private val logger = KotlinLogging.logger {}

    private val _connectionsFlow = MutableStateFlow<List<ConnectionResponseDto>>(emptyList())

    init {
        runBlocking {
            _connectionsFlow.value = connectionService.findAll().map { it.toDto() }.toList()
        }
    }

    override suspend fun getAllConnections(): List<ConnectionResponseDto> =
        connectionService.findAll().map { it.toDto() }.toList()

    override suspend fun getConnectionById(id: String): ConnectionResponseDto? =
        connectionService.findById(ConnectionId.fromString(id))?.toDto()

    override suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto {
        val provider = request.provider
        val protocol = request.protocol
        val authType = request.authType

        val descriptor = providerRegistry.getDescriptorOrNull(provider)
        val capabilities = descriptor?.capabilities ?: emptySet()

        val baseUrl = request.baseUrl?.takeIf { it.isNotBlank() }
            ?: (if (request.isCloud) descriptor?.defaultCloudBaseUrl else null)
            ?: ""

        val connectionDocument = ConnectionDocument(
            name = request.name,
            provider = provider,
            protocol = protocol,
            authType = authType,
            state = request.state,
            availableCapabilities = capabilities,
            isCloud = request.isCloud,
            baseUrl = baseUrl,
            timeoutMs = request.timeoutMs ?: 30000,
            username = request.username,
            password = request.password,
            bearerToken = request.bearerToken,
            authorizationUrl = request.authorizationUrl,
            tokenUrl = request.tokenUrl,
            clientSecret = request.clientSecret,
            scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            redirectUri = request.redirectUri,
            host = request.host,
            port = request.port ?: getDefaultPort(protocol),
            useSsl = request.useSsl ?: true,
            useTls = request.useTls,
            folderName = request.folderName ?: "INBOX",
            rateLimitConfig = request.rateLimitConfig?.toEntity()
                ?: ConnectionDocument.RateLimitConfig(10, 100),
            jiraProjectKey = request.jiraProjectKey,
            confluenceSpaceKey = request.confluenceSpaceKey,
            confluenceRootPageId = request.confluenceRootPageId,
            bitbucketRepoSlug = request.bitbucketRepoSlug,
            gitRemoteUrl = request.gitRemoteUrl,
        )

        val created = connectionService.save(connectionDocument)
        logger.info { "Created connection: ${created.name} (${created.id}) - provider=$provider, protocol=$protocol, authType=$authType" }
        val updatedList = connectionService.findAll().map { it.toDto() }.toList()
        _connectionsFlow.emit(updatedList)
        return created.toDto()
    }

    override suspend fun updateConnection(
        id: String,
        request: ConnectionUpdateRequestDto,
    ): ConnectionResponseDto {
        logger.info { "Updating connection: $id" }
        val existing =
            connectionService.findById(ConnectionId.fromString(id))
                ?: throw IllegalArgumentException("ConnectionDocument not found: $id")

        val newProvider = request.provider ?: existing.provider

        val capabilities = if (request.provider != null) {
            providerRegistry.getDescriptorOrNull(newProvider)?.capabilities ?: existing.availableCapabilities
        } else {
            existing.availableCapabilities
        }

        val newIsCloud = request.isCloud ?: existing.isCloud
        val newBaseUrl = if (newIsCloud && request.baseUrl == null) {
            // Cloud mode: use default cloud URL from descriptor if no explicit baseUrl
            providerRegistry.getDescriptorOrNull(newProvider)?.defaultCloudBaseUrl ?: existing.baseUrl
        } else {
            request.baseUrl ?: existing.baseUrl
        }

        val updated = existing.copy(
            name = request.name ?: existing.name,
            provider = newProvider,
            protocol = request.protocol ?: existing.protocol,
            authType = request.authType ?: existing.authType,
            availableCapabilities = capabilities,
            isCloud = newIsCloud,
            baseUrl = newBaseUrl,
            timeoutMs = request.timeoutMs ?: existing.timeoutMs,
            username = request.username ?: existing.username,
            password = request.password ?: existing.password,
            bearerToken = request.bearerToken ?: existing.bearerToken,
            authorizationUrl = request.authorizationUrl ?: existing.authorizationUrl,
            tokenUrl = request.tokenUrl ?: existing.tokenUrl,
            clientSecret = request.clientSecret ?: existing.clientSecret,
            scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: existing.scopes,
            redirectUri = request.redirectUri ?: existing.redirectUri,
            host = request.host ?: existing.host,
            port = request.port ?: existing.port,
            useSsl = request.useSsl ?: existing.useSsl,
            useTls = request.useTls ?: existing.useTls,
            folderName = request.folderName ?: existing.folderName,
            rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
            jiraProjectKey = request.jiraProjectKey ?: existing.jiraProjectKey,
            confluenceSpaceKey = request.confluenceSpaceKey ?: existing.confluenceSpaceKey,
            confluenceRootPageId = request.confluenceRootPageId ?: existing.confluenceRootPageId,
            bitbucketRepoSlug = request.bitbucketRepoSlug ?: existing.bitbucketRepoSlug,
            gitRemoteUrl = request.gitRemoteUrl ?: existing.gitRemoteUrl,
        )

        val saved = connectionService.save(updated)
        logger.info { "Updated connection: ${saved.name}" }
        val updatedList = connectionService.findAll().map { it.toDto() }.toList()
        _connectionsFlow.emit(updatedList)
        return saved.toDto()
    }

    override suspend fun deleteConnection(id: String) {
        connectionService.delete(ConnectionId.fromString(id))
        logger.info { "Deleted connection: $id" }
        val updatedList = connectionService.findAll().map { it.toDto() }.toList()
        _connectionsFlow.emit(updatedList)
    }

    override suspend fun testConnection(id: String): ConnectionTestResultDto {
        val connection =
            connectionService.findById(ConnectionId.fromString(id))
                ?: throw IllegalArgumentException("Connection not found: $id")

        return try {
            val client = providerRegistry.getClient(connection.provider)
            client.testConnection(connection.toTestRequest())
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
            )
        }
    }

    override suspend fun initiateOAuth2(connectionId: String): String {
        connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")
        return oauth2Service.getAuthorizationUrl(ConnectionId.fromString(connectionId)).authorizationUrl
    }

    override suspend fun listAvailableResources(
        connectionId: String,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val connection =
            connectionService.findById(ConnectionId.fromString(connectionId))
                ?: throw IllegalArgumentException("Connection not found: $connectionId")

        return try {
            val client = providerRegistry.getClient(connection.provider)
            client.listResources(connection.toListResourcesRequest(capability))
        } catch (e: Exception) {
            logger.error(e) { "Failed to list resources for connection ${connection.id}" }
            emptyList()
        }
    }

    override suspend fun listImportableProjects(connectionId: String): List<com.jervis.dto.connection.ConnectionImportProjectDto> {
        logger.warn { "listImportableProjects not yet implemented for connection $connectionId" }
        return emptyList()
    }

    override suspend fun importProject(
        connectionId: String,
        externalId: String,
    ): com.jervis.dto.ProjectDto {
        throw UnsupportedOperationException("importProject not yet implemented")
    }

    override suspend fun getProviderDescriptors(): List<ProviderDescriptor> =
        providerRegistry.getAllDescriptors().values.toList()

    private fun getDefaultPort(protocol: ProtocolEnum): Int = when (protocol) {
        ProtocolEnum.HTTP -> 443
        ProtocolEnum.IMAP -> 993
        ProtocolEnum.POP3 -> 995
        ProtocolEnum.SMTP -> 587
    }
}

private fun ConnectionDocument.toTestRequest(): ProviderTestRequest =
    ProviderTestRequest(
        baseUrl = baseUrl,
        protocol = protocol,
        authType = authType,
        username = username,
        password = password,
        bearerToken = bearerToken,
        host = host,
        port = port,
        useSsl = useSsl,
        useTls = useTls,
        folderName = folderName,
        cloudId = cloudId,
    )

private fun ConnectionDocument.toListResourcesRequest(capability: ConnectionCapability): ProviderListResourcesRequest =
    ProviderListResourcesRequest(
        baseUrl = baseUrl,
        protocol = protocol,
        authType = authType,
        username = username,
        password = password,
        bearerToken = bearerToken,
        capability = capability,
        host = host,
        port = port,
        useSsl = useSsl,
        useTls = useTls,
        cloudId = cloudId,
    )

private fun ConnectionDocument.toDto(): ConnectionResponseDto =
    ConnectionResponseDto(
        id = id.toString(),
        provider = provider,
        protocol = protocol,
        authType = authType,
        name = name,
        state = state,
        capabilities = availableCapabilities,
        isCloud = isCloud,
        baseUrl = baseUrl,
        timeoutMs = timeoutMs,
        username = username,
        password = password,
        bearerToken = bearerToken,
        authorizationUrl = authorizationUrl,
        tokenUrl = tokenUrl,
        clientSecret = clientSecret,
        redirectUri = redirectUri,
        scope = scopes.joinToString(" "),
        host = host,
        port = port,
        useSsl = useSsl,
        useTls = useTls,
        folderName = folderName,
        rateLimitConfig = rateLimitConfig.toDto(),
        jiraProjectKey = jiraProjectKey,
        confluenceSpaceKey = confluenceSpaceKey,
        confluenceRootPageId = confluenceRootPageId,
        bitbucketRepoSlug = bitbucketRepoSlug,
        gitRemoteUrl = gitRemoteUrl,
    )

private fun ConnectionDocument.RateLimitConfig.toDto(): RateLimitConfigDto =
    RateLimitConfigDto(
        maxRequestsPerSecond = maxRequestsPerSecond,
        maxRequestsPerMinute = maxRequestsPerMinute,
    )

private fun RateLimitConfigDto.toEntity(): ConnectionDocument.RateLimitConfig =
    ConnectionDocument.RateLimitConfig(
        maxRequestsPerSecond = maxRequestsPerSecond,
        maxRequestsPerMinute = maxRequestsPerMinute,
    )
