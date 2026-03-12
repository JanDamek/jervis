package com.jervis.rpc

import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionStateEnum
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
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
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
    private val httpClient: io.ktor.client.HttpClient,
    @org.springframework.beans.factory.annotation.Value("\${jervis.o365-gateway.url:http://jervis-o365-gateway:8080}")
    private val o365GatewayUrl: String = "http://jervis-o365-gateway:8080",
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
            o365ClientId = request.o365ClientId,
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
            o365ClientId = request.o365ClientId ?: existing.o365ClientId,
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

        // Attempt proactive token refresh for OAuth2 connections before making API call
        val refreshedConnection = refreshTokenIfNeeded(connection)

        return try {
            val result = providerRegistry.withClient(refreshedConnection.provider) { it.testConnection(refreshedConnection.toTestRequest()) }
            refreshedConnection.state = if (result.success) ConnectionStateEnum.VALID else ConnectionStateEnum.INVALID
            connectionService.save(refreshedConnection)
            result
        } catch (e: com.jervis.common.http.ProviderAuthException) {
            // Token expired or revoked — attempt reactive refresh and retry once
            logger.warn { "Auth error testing connection ${refreshedConnection.name}: ${e.message}" }
            val retryConnection = attemptReactiveRefresh(refreshedConnection)
            if (retryConnection != null) {
                try {
                    val retryResult = providerRegistry.withClient(retryConnection.provider) { it.testConnection(retryConnection.toTestRequest()) }
                    retryConnection.state = if (retryResult.success) ConnectionStateEnum.VALID else ConnectionStateEnum.INVALID
                    connectionService.save(retryConnection)
                    return retryResult
                } catch (retryEx: Exception) {
                    logger.warn { "Retry after token refresh also failed for ${retryConnection.name}: ${retryEx.message}" }
                }
            }
            // Refresh failed or retry failed — mark as AUTH_EXPIRED
            connectionService.save(refreshedConnection.copy(state = ConnectionStateEnum.AUTH_EXPIRED))
            ConnectionTestResultDto(
                success = false,
                message = "Token expiroval. Proveďte re-autorizaci OAuth2 připojení.",
            )
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${refreshedConnection.name}" }
            refreshedConnection.state = ConnectionStateEnum.INVALID
            connectionService.save(refreshedConnection)
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

        // MICROSOFT_TEAMS: list resources directly via O365 Gateway (no ProviderRegistry)
        if (connection.provider == ProviderEnum.MICROSOFT_TEAMS) {
            return listO365Resources(connection, capability)
        }

        // SLACK: list channels via Slack Web API (no ProviderRegistry)
        if (connection.provider == ProviderEnum.SLACK) {
            return listSlackResources(connection, capability)
        }

        // DISCORD: list guilds/channels via Discord API (no ProviderRegistry)
        if (connection.provider == ProviderEnum.DISCORD) {
            return listDiscordResources(connection, capability)
        }

        // Attempt proactive token refresh for OAuth2 connections before making API call
        val refreshedConnection = refreshTokenIfNeeded(connection)

        return try {
            val resources = providerRegistry.withClient(refreshedConnection.provider) {
                it.listResources(refreshedConnection.toListResourcesRequest(capability))
            }
            resources
        } catch (e: com.jervis.common.http.ProviderAuthException) {
            // Token expired or revoked — attempt reactive refresh and retry once
            logger.warn { "Auth error for connection ${refreshedConnection.id} (${refreshedConnection.provider}): ${e.message}" }
            val retryConnection = attemptReactiveRefresh(refreshedConnection)
            if (retryConnection != null) {
                try {
                    val retryResources = providerRegistry.withClient(retryConnection.provider) {
                        it.listResources(retryConnection.toListResourcesRequest(capability))
                    }
                    logger.info { "Retry after token refresh succeeded for connection ${retryConnection.id}" }
                    return retryResources
                } catch (retryEx: Exception) {
                    logger.warn { "Retry after token refresh also failed for ${retryConnection.id}: ${retryEx.message}" }
                }
            }
            // Refresh failed or retry failed — mark connection as AUTH_EXPIRED
            try {
                connectionService.save(refreshedConnection.copy(state = ConnectionStateEnum.AUTH_EXPIRED))
            } catch (saveErr: Exception) {
                logger.error(saveErr) { "Failed to update connection state to AUTH_EXPIRED" }
            }
            emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list resources for connection ${refreshedConnection.id}" }
            emptyList()
        }
    }

    /**
     * Proactive refresh: refresh OAuth2 access token if it's about to expire.
     * Returns the same connection if no refresh is needed or refresh fails.
     */
    private suspend fun refreshTokenIfNeeded(connection: ConnectionDocument): ConnectionDocument {
        // Only refresh for OAuth2 connections
        if (connection.authType != com.jervis.dto.connection.AuthTypeEnum.OAUTH2) {
            return connection
        }

        // Attempt token refresh (proactive, time-based check)
        val refreshed = oauth2Service.refreshAccessToken(connection)
        if (!refreshed) {
            // Token refresh not needed or failed, return original connection
            return connection
        }

        // Reload connection to get the updated token
        return connectionService.findById(connection.id) ?: connection
    }

    /**
     * Reactive refresh: force-refresh the OAuth2 token after a 401 error.
     * Returns the refreshed connection or null if refresh is not possible/failed.
     */
    private suspend fun attemptReactiveRefresh(connection: ConnectionDocument): ConnectionDocument? {
        if (connection.authType != com.jervis.dto.connection.AuthTypeEnum.OAUTH2) return null

        logger.info { "Attempting reactive token refresh for connection ${connection.id} (${connection.name})" }
        val refreshed = oauth2Service.refreshAccessToken(connection, force = true)
        if (!refreshed) {
            logger.warn { "Reactive token refresh failed for connection ${connection.id}" }
            return null
        }

        return connectionService.findById(connection.id)
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

    /**
     * List available O365 resources (teams/channels) via O365 Gateway for MICROSOFT_TEAMS connections.
     * Returns channels as resources with id = "teamId/channelId" so they match the polling routing key.
     */
    private suspend fun listO365Resources(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val clientId = connection.o365ClientId
        if (clientId.isNullOrBlank()) {
            logger.warn { "O365 connection ${connection.id} has no o365ClientId" }
            return emptyList()
        }

        if (capability != ConnectionCapability.CHAT_READ && capability != ConnectionCapability.CHAT_SEND) {
            return emptyList()
        }

        return try {
            val teams = httpClient.get("$o365GatewayUrl/api/o365/teams/$clientId")
            if (!teams.status.isSuccess()) {
                logger.warn { "Failed to fetch teams from O365 Gateway: ${teams.status}" }
                return emptyList()
            }

            val teamList = teams.body<List<O365TeamDto>>()
            val resources = mutableListOf<ConnectionResourceDto>()

            for (team in teamList) {
                val teamId = team.id ?: continue
                val teamName = team.displayName ?: "Team"

                val channels = httpClient.get("$o365GatewayUrl/api/o365/teams/$clientId/$teamId/channels")
                if (!channels.status.isSuccess()) continue
                val channelList = channels.body<List<O365ChannelDto>>()

                for (channel in channelList) {
                    val channelId = channel.id ?: continue
                    val channelName = channel.displayName ?: "Channel"
                    resources.add(
                        ConnectionResourceDto(
                            id = "$teamId/$channelId",
                            name = "$teamName / $channelName",
                            description = channel.description,
                            capability = capability,
                        ),
                    )
                }
            }

            resources
        } catch (e: Exception) {
            logger.error(e) { "Failed to list O365 resources for connection ${connection.id}" }
            emptyList()
        }
    }

    /**
     * List available Slack channels via Slack Web API.
     * Returns channels as resources with id = channelId.
     */
    private suspend fun listSlackResources(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val token = connection.bearerToken
        if (token.isNullOrBlank()) {
            logger.warn { "Slack connection ${connection.id} has no bot token" }
            return emptyList()
        }

        if (capability != ConnectionCapability.CHAT_READ && capability != ConnectionCapability.CHAT_SEND) {
            return emptyList()
        }

        return try {
            val response = httpClient.get("https://slack.com/api/conversations.list?types=public_channel,private_channel&limit=200&exclude_archived=true") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch Slack channels: ${response.status}" }
                return emptyList()
            }
            val body = response.body<SlackChannelsListDto>()
            if (!body.ok) {
                logger.warn { "Slack API error: ${body.error}" }
                return emptyList()
            }
            body.channels.mapNotNull { ch ->
                val id = ch.id ?: return@mapNotNull null
                ConnectionResourceDto(
                    id = id,
                    name = "#${ch.name ?: id}",
                    capability = capability,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Slack resources for connection ${connection.id}" }
            emptyList()
        }
    }

    /**
     * List available Discord guild channels via Discord REST API.
     * Returns channels as resources with id = "guildId/channelId".
     */
    private suspend fun listDiscordResources(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val token = connection.bearerToken
        if (token.isNullOrBlank()) {
            logger.warn { "Discord connection ${connection.id} has no bot token" }
            return emptyList()
        }

        if (capability != ConnectionCapability.CHAT_READ && capability != ConnectionCapability.CHAT_SEND) {
            return emptyList()
        }

        return try {
            val guilds = httpClient.get("https://discord.com/api/v10/users/@me/guilds") {
                header("Authorization", "Bot $token")
            }
            if (!guilds.status.isSuccess()) {
                logger.warn { "Failed to fetch Discord guilds: ${guilds.status}" }
                return emptyList()
            }
            val guildList = guilds.body<List<DiscordGuildDto>>()
            val resources = mutableListOf<ConnectionResourceDto>()

            for (guild in guildList) {
                val guildId = guild.id ?: continue
                val guildName = guild.name ?: "Server"

                val channels = httpClient.get("https://discord.com/api/v10/guilds/$guildId/channels") {
                    header("Authorization", "Bot $token")
                }
                if (!channels.status.isSuccess()) continue
                val channelList = channels.body<List<DiscordChannelDto>>()

                // Only text channels (type 0)
                for (channel in channelList.filter { it.type == 0 }) {
                    val channelId = channel.id ?: continue
                    val channelName = channel.name ?: "channel"
                    resources.add(
                        ConnectionResourceDto(
                            id = "$guildId/$channelId",
                            name = "$guildName / #$channelName",
                            capability = capability,
                        ),
                    )
                }
            }

            resources
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Discord resources for connection ${connection.id}" }
            emptyList()
        }
    }

    @kotlinx.serialization.Serializable
    private data class O365TeamDto(
        val id: String? = null,
        val displayName: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class O365ChannelDto(
        val id: String? = null,
        val displayName: String? = null,
        val description: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class SlackChannelsListDto(
        val ok: Boolean = false,
        val channels: List<SlackChannelDto> = emptyList(),
        val error: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class SlackChannelDto(
        val id: String? = null,
        val name: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class DiscordGuildDto(
        val id: String? = null,
        val name: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class DiscordChannelDto(
        val id: String? = null,
        val name: String? = null,
        val type: Int = 0,
    )
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
        o365ClientId = o365ClientId,
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
