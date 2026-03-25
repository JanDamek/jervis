package com.jervis.rpc

import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.BrowserSessionStatusDto
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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    private val discoveredResourceRepository: com.jervis.repository.O365DiscoveredResourceRepository,
    @org.springframework.beans.factory.annotation.Value("\${jervis.o365-gateway.url:http://jervis-o365-gateway:8080}")
    private val o365GatewayUrl: String = "http://jervis-o365-gateway:8080",
    @org.springframework.beans.factory.annotation.Value("\${jervis.o365-browser-pool.url:http://jervis-o365-browser-pool:8090}")
    private val o365BrowserPoolUrl: String = "http://jervis-o365-browser-pool:8090",
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
            ?: ProviderDescriptor.defaultsByProvider[provider]
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
            // Use connectionId as o365ClientId for browser pool (unique, not name-based)
            o365ClientId = request.o365ClientId,
            isJervisOwned = request.isJervisOwned,
        )

        // For Teams browser session without explicit o365ClientId, use the generated connectionId
        val toSave = if (provider == ProviderEnum.MICROSOFT_TEAMS && connectionDocument.o365ClientId == null) {
            connectionDocument.copy(o365ClientId = connectionDocument.id.toString())
        } else {
            connectionDocument
        }

        val created = connectionService.save(toSave)
        logger.info { "Created connection: ${created.name} (${created.id}) - provider=$provider, protocol=$protocol, authType=$authType" }

        // Auto-init browser pool session for Teams Browser Session auth
        if (provider == ProviderEnum.MICROSOFT_TEAMS && authType == com.jervis.dto.connection.AuthTypeEnum.NONE) {
            initBrowserPoolSession(created)
        }

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

        val newDescriptor = providerRegistry.getDescriptorOrNull(newProvider)
            ?: ProviderDescriptor.defaultsByProvider[newProvider]
        val capabilities = if (request.provider != null) {
            newDescriptor?.capabilities ?: existing.availableCapabilities
        } else {
            // Always refresh capabilities from descriptor (may have been updated)
            newDescriptor?.capabilities ?: existing.availableCapabilities
        }

        val newIsCloud = request.isCloud ?: existing.isCloud
        val newBaseUrl = if (newIsCloud && request.baseUrl == null) {
            // Cloud mode: use default cloud URL from descriptor if no explicit baseUrl
            newDescriptor?.defaultCloudBaseUrl ?: existing.baseUrl
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
            isJervisOwned = request.isJervisOwned ?: existing.isJervisOwned,
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

        // Non-registry providers: test via their specific APIs
        if (providerRegistry.getDescriptorOrNull(connection.provider) == null) {
            return testNonRegistryConnection(connection)
        }

        // Attempt proactive token refresh for OAuth2 connections before making API call
        val refreshedConnection = refreshTokenIfNeeded(connection)

        return try {
            val result = providerRegistry.withClient(refreshedConnection.provider) { it.testConnection(refreshedConnection.toTestRequest()) }
            refreshedConnection.state = if (result.success) ConnectionStateEnum.VALID else ConnectionStateEnum.INVALID
            // Auto-detect self-identity on successful test
            val toSave = if (result.success) {
                detectSelfIdentity(refreshedConnection)
            } else {
                refreshedConnection
            }
            connectionService.save(toSave)
            result
        } catch (e: com.jervis.common.http.ProviderAuthException) {
            // Token expired or revoked — attempt reactive refresh and retry once
            logger.warn { "Auth error testing connection ${refreshedConnection.name}: ${e.message}" }
            val retryConnection = attemptReactiveRefresh(refreshedConnection)
            if (retryConnection != null) {
                try {
                    val retryResult = providerRegistry.withClient(retryConnection.provider) { it.testConnection(retryConnection.toTestRequest()) }
                    retryConnection.state = if (retryResult.success) ConnectionStateEnum.VALID else ConnectionStateEnum.INVALID
                    val retryToSave = if (retryResult.success) detectSelfIdentity(retryConnection) else retryConnection
                    connectionService.save(retryToSave)
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

    /**
     * Test connections that are not in the ProviderRegistry (Teams, Google, Slack, Discord).
     * Validates by making a simple API call with the connection's credentials.
     */
    private suspend fun testNonRegistryConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        return try {
            when (connection.provider) {
                ProviderEnum.MICROSOFT_TEAMS -> testMicrosoftConnection(connection)
                ProviderEnum.GOOGLE_WORKSPACE -> testGoogleConnection(connection)
                ProviderEnum.SLACK -> testSlackConnection(connection)
                ProviderEnum.DISCORD -> testDiscordConnection(connection)
                else -> ConnectionTestResultDto(false, "Test pro ${connection.provider} není implementován")
            }
        } catch (e: Exception) {
            logger.error(e) { "Test failed for ${connection.name}: ${e.message}" }
            connectionService.save(connection.copy(state = ConnectionStateEnum.INVALID))
            ConnectionTestResultDto(false, "Test selhal: ${e.message}")
        }
    }

    private suspend fun testMicrosoftConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val refreshed = refreshTokenIfNeeded(connection)
        val token = refreshed.bearerToken
        if (token.isNullOrBlank()) {
            // Browser Session: check if O365 Gateway can reach the browser pool
            val clientId = connection.o365ClientId
            if (!clientId.isNullOrBlank()) {
                val response = httpClient.get("$o365BrowserPoolUrl/session/$clientId")
                return if (response.status.isSuccess()) {
                    val withIdentity = detectSelfIdentity(connection.copy(state = ConnectionStateEnum.VALID))
                    connectionService.save(withIdentity)
                    ConnectionTestResultDto(true, "Browser session aktivní")
                } else {
                    connectionService.save(connection.copy(state = ConnectionStateEnum.INVALID))
                    ConnectionTestResultDto(false, "Browser session neaktivní — přihlaste se přes noVNC")
                }
            }
            return ConnectionTestResultDto(false, "Žádný token ani browser session")
        }
        // OAuth2: test with /me endpoint
        val response = httpClient.get("https://graph.microsoft.com/v1.0/me") {
            header("Authorization", "Bearer $token")
        }
        return if (response.status.isSuccess()) {
            // Auto-detect self-identity from /me response
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            val withIdentity = refreshed.copy(
                state = ConnectionStateEnum.VALID,
                selfUsername = body?.get("userPrincipalName")?.jsonPrimitive?.contentOrNull ?: refreshed.selfUsername,
                selfDisplayName = body?.get("displayName")?.jsonPrimitive?.contentOrNull ?: refreshed.selfDisplayName,
                selfId = body?.get("id")?.jsonPrimitive?.contentOrNull ?: refreshed.selfId,
                selfEmail = body?.get("mail")?.jsonPrimitive?.contentOrNull ?: refreshed.selfEmail,
            )
            connectionService.save(withIdentity)
            ConnectionTestResultDto(true, "Microsoft Graph API OK")
        } else {
            connectionService.save(refreshed.copy(state = ConnectionStateEnum.AUTH_EXPIRED))
            ConnectionTestResultDto(false, "Microsoft Graph API: ${response.status}")
        }
    }

    private suspend fun testGoogleConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val refreshed = refreshTokenIfNeeded(connection)
        val token = refreshed.bearerToken
        if (token.isNullOrBlank()) {
            return ConnectionTestResultDto(false, "Žádný OAuth2 token")
        }
        val response = httpClient.get("https://www.googleapis.com/oauth2/v1/userinfo") {
            header("Authorization", "Bearer $token")
        }
        return if (response.status.isSuccess()) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            val withIdentity = refreshed.copy(
                state = ConnectionStateEnum.VALID,
                selfUsername = body?.get("email")?.jsonPrimitive?.contentOrNull ?: refreshed.selfUsername,
                selfDisplayName = body?.get("name")?.jsonPrimitive?.contentOrNull ?: refreshed.selfDisplayName,
                selfId = body?.get("id")?.jsonPrimitive?.contentOrNull ?: refreshed.selfId,
                selfEmail = body?.get("email")?.jsonPrimitive?.contentOrNull ?: refreshed.selfEmail,
            )
            connectionService.save(withIdentity)
            ConnectionTestResultDto(true, "Google API OK")
        } else {
            connectionService.save(refreshed.copy(state = ConnectionStateEnum.AUTH_EXPIRED))
            ConnectionTestResultDto(false, "Google API: ${response.status}")
        }
    }

    private suspend fun testSlackConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val token = connection.bearerToken
        if (token.isNullOrBlank()) {
            return ConnectionTestResultDto(false, "Žádný Slack token")
        }
        val response = httpClient.get("https://slack.com/api/auth.test") {
            header("Authorization", "Bearer $token")
        }
        return if (response.status.isSuccess()) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            val withIdentity = connection.copy(
                state = ConnectionStateEnum.VALID,
                selfUsername = body?.get("user")?.jsonPrimitive?.contentOrNull ?: connection.selfUsername,
                selfId = body?.get("user_id")?.jsonPrimitive?.contentOrNull ?: connection.selfId,
            )
            connectionService.save(withIdentity)
            ConnectionTestResultDto(true, "Slack API OK")
        } else {
            connectionService.save(connection.copy(state = ConnectionStateEnum.INVALID))
            ConnectionTestResultDto(false, "Slack API: ${response.status}")
        }
    }

    private suspend fun testDiscordConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val token = connection.bearerToken
        if (token.isNullOrBlank()) {
            return ConnectionTestResultDto(false, "Žádný Discord token")
        }
        val response = httpClient.get("https://discord.com/api/v10/users/@me") {
            header("Authorization", "Bot $token")
        }
        return if (response.status.isSuccess()) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            val withIdentity = connection.copy(
                state = ConnectionStateEnum.VALID,
                selfUsername = body?.get("username")?.jsonPrimitive?.contentOrNull ?: connection.selfUsername,
                selfDisplayName = body?.get("global_name")?.jsonPrimitive?.contentOrNull ?: connection.selfDisplayName,
                selfId = body?.get("id")?.jsonPrimitive?.contentOrNull ?: connection.selfId,
                selfEmail = body?.get("email")?.jsonPrimitive?.contentOrNull ?: connection.selfEmail,
            )
            connectionService.save(withIdentity)
            ConnectionTestResultDto(true, "Discord API OK")
        } else {
            connectionService.save(connection.copy(state = ConnectionStateEnum.INVALID))
            ConnectionTestResultDto(false, "Discord API: ${response.status}")
        }
    }

    /**
     * Auto-detect self-identity for registry-based providers (GitHub, GitLab, Atlassian).
     * Calls the provider's user endpoint and stores username/id/displayName/email.
     * Only updates fields that are currently null (doesn't overwrite manual settings).
     */
    private suspend fun detectSelfIdentity(connection: ConnectionDocument): ConnectionDocument {
        if (connection.selfUsername != null && connection.selfId != null) {
            // Already populated, skip detection
            return connection
        }

        return try {
            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val apiUrl = connection.baseUrl.takeIf { it.isNotBlank() } ?: "https://api.github.com"
                    val token = connection.bearerToken ?: return connection
                    val response = httpClient.get("${apiUrl.trimEnd('/')}/user") {
                        header("Authorization", "Bearer $token")
                        header("Accept", "application/vnd.github+json")
                    }
                    if (response.status.isSuccess()) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        connection.copy(
                            selfUsername = connection.selfUsername ?: body["login"]?.jsonPrimitive?.contentOrNull,
                            selfId = connection.selfId ?: body["id"]?.jsonPrimitive?.contentOrNull,
                            selfDisplayName = connection.selfDisplayName ?: body["name"]?.jsonPrimitive?.contentOrNull,
                            selfEmail = connection.selfEmail ?: body["email"]?.jsonPrimitive?.contentOrNull,
                        )
                    } else connection
                }

                ProviderEnum.GITLAB -> {
                    val apiUrl = connection.baseUrl.takeIf { it.isNotBlank() } ?: "https://gitlab.com"
                    val token = connection.bearerToken ?: return connection
                    val response = httpClient.get("${apiUrl.trimEnd('/')}/api/v4/user") {
                        header("Authorization", "Bearer $token")
                    }
                    if (response.status.isSuccess()) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        connection.copy(
                            selfUsername = connection.selfUsername ?: body["username"]?.jsonPrimitive?.contentOrNull,
                            selfId = connection.selfId ?: body["id"]?.jsonPrimitive?.contentOrNull,
                            selfDisplayName = connection.selfDisplayName ?: body["name"]?.jsonPrimitive?.contentOrNull,
                            selfEmail = connection.selfEmail ?: body["email"]?.jsonPrimitive?.contentOrNull,
                        )
                    } else connection
                }

                ProviderEnum.ATLASSIAN -> {
                    val token = connection.bearerToken ?: return connection
                    val cloudId = connection.cloudId
                    // Jira Cloud uses /rest/api/3/myself with cloudId
                    val url = if (cloudId != null) {
                        "https://api.atlassian.com/ex/jira/$cloudId/rest/api/3/myself"
                    } else {
                        val baseUrl = connection.baseUrl.takeIf { it.isNotBlank() } ?: return connection
                        "${baseUrl.trimEnd('/')}/rest/api/3/myself"
                    }
                    val response = httpClient.get(url) {
                        header("Authorization", "Bearer $token")
                        header("Accept", "application/json")
                    }
                    if (response.status.isSuccess()) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        connection.copy(
                            selfUsername = connection.selfUsername ?: body["emailAddress"]?.jsonPrimitive?.contentOrNull,
                            selfId = connection.selfId ?: body["accountId"]?.jsonPrimitive?.contentOrNull,
                            selfDisplayName = connection.selfDisplayName ?: body["displayName"]?.jsonPrimitive?.contentOrNull,
                            selfEmail = connection.selfEmail ?: body["emailAddress"]?.jsonPrimitive?.contentOrNull,
                        )
                    } else connection
                }

                else -> connection // Non-DevOps providers are handled in their test methods
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to auto-detect self-identity for ${connection.name} (${connection.provider}), continuing without" }
            connection
        }
    }

    override suspend fun initiateOAuth2(connectionId: String, forceLogin: Boolean): String {
        connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")
        return oauth2Service.getAuthorizationUrl(ConnectionId.fromString(connectionId), forceLogin).authorizationUrl
    }

    override suspend fun initBrowserSession(connectionId: String): String {
        val connection = connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")
        return initBrowserPoolSession(connection)
    }

    override suspend fun getBrowserSessionStatus(connectionId: String): BrowserSessionStatusDto {
        val connection = connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")

        val clientId = connection.o365ClientId
            ?: return BrowserSessionStatusDto(
                state = "ERROR",
                message = "Připojení nemá nastavené o365ClientId",
            )

        return try {
            // Get session status from browser pool
            val response = httpClient.get("$o365BrowserPoolUrl/session/$clientId")
            if (!response.status.isSuccess()) {
                return BrowserSessionStatusDto(
                    state = "ERROR",
                    message = "Browser pool nedostupný: ${response.status}",
                )
            }

            val json = Json { ignoreUnknownKeys = true }
            val sessionJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            var state = sessionJson["state"]?.jsonPrimitive?.content ?: "ERROR"
            val hasToken = sessionJson["has_token"]?.jsonPrimitive?.booleanOrNull ?: false

            // Auto re-init session only if EXPIRED (not PENDING_LOGIN — user may be logging in)
            if (state == "EXPIRED") {
                try {
                    val initResponse = httpClient.post("$o365BrowserPoolUrl/session/$clientId/init")
                    if (initResponse.status.isSuccess()) {
                        state = "PENDING_LOGIN"
                        logger.info { "Auto re-init browser session for $clientId (was EXPIRED)" }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to auto re-init session for $clientId: ${e.message}" }
                }
            }

            // Update connection state if token was captured
            // Set DISCOVERING (not VALID) — capabilities callback will finalize the state
            if (hasToken && state == "ACTIVE" &&
                connection.state != ConnectionStateEnum.VALID &&
                connection.state != ConnectionStateEnum.DISCOVERING
            ) {
                connectionService.save(connection.copy(state = ConnectionStateEnum.DISCOVERING))
            }

            // Generate one-time VNC token if login is needed (not active = needs login/re-login)
            var vncUrl: String? = null
            if (state != "ACTIVE") {
                try {
                    val tokenResponse = httpClient.post("$o365BrowserPoolUrl/vnc-token/$clientId")
                    if (tokenResponse.status.isSuccess()) {
                        val tokenJson = json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
                        val vncToken = tokenJson["token"]?.jsonPrimitive?.content
                        if (vncToken != null) {
                            vncUrl = "https://jervis-vnc.damek-soft.eu/vnc-login?token=$vncToken"
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to generate VNC token for $clientId: ${e.message}" }
                }
            }

            // MFA info
            val mfaType = sessionJson["mfa_type"]?.jsonPrimitive?.contentOrNull
            val mfaMessage = sessionJson["mfa_message"]?.jsonPrimitive?.contentOrNull
            val mfaNumber = sessionJson["mfa_number"]?.jsonPrimitive?.contentOrNull

            val message = when (state) {
                "PENDING_LOGIN" -> "Čeká na přihlášení — otevřete okno pro Microsoft login"
                "ACTIVE" -> "Přihlášení úspěšné!"
                "AWAITING_MFA" -> mfaMessage ?: "Vyžadováno dvoufaktorové ověření"
                "EXPIRED" -> "Token expiroval — je nutné se znovu přihlásit"
                "ERROR" -> "Chyba browser session"
                else -> state
            }

            BrowserSessionStatusDto(
                state = state,
                hasToken = hasToken,
                vncUrl = vncUrl,
                message = message,
                mfaType = mfaType,
                mfaMessage = mfaMessage,
                mfaNumber = mfaNumber,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get browser session status for $clientId" }
            BrowserSessionStatusDto(
                state = "ERROR",
                message = "Chyba komunikace s browser pool: ${e.message}",
            )
        }
    }

    override suspend fun submitBrowserSessionMfa(connectionId: String, code: String): BrowserSessionStatusDto {
        val connection = connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")

        val clientId = connection.o365ClientId
            ?: return BrowserSessionStatusDto(state = "ERROR", message = "Připojení nemá o365ClientId")

        return try {
            val response = httpClient.post("$o365BrowserPoolUrl/session/$clientId/mfa") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("code", code) }.toString())
            }
            val responseText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            val state = responseJson["state"]?.jsonPrimitive?.content ?: "ERROR"
            val message = responseJson["message"]?.jsonPrimitive?.content

            if (state == "ACTIVE") {
                // Set DISCOVERING — capabilities callback from browser pool will transition to VALID
                connectionService.save(connection.copy(state = ConnectionStateEnum.DISCOVERING))
            }

            BrowserSessionStatusDto(
                state = state,
                hasToken = state == "ACTIVE",
                message = message ?: if (state == "ACTIVE") "MFA ověřeno — zjišťuji dostupné služby..." else "MFA ověření",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to submit MFA for $clientId" }
            BrowserSessionStatusDto(state = "ERROR", message = "Chyba: ${e.message}")
        }
    }

    override suspend fun rediscoverCapabilities(connectionId: String): BrowserSessionStatusDto {
        val connection = connectionService.findById(ConnectionId.fromString(connectionId))
            ?: throw IllegalArgumentException("Connection not found: $connectionId")

        val clientId = connection.o365ClientId
            ?: return BrowserSessionStatusDto(state = "ERROR", message = "Připojení nemá o365ClientId")

        // Set state to DISCOVERING while re-checking tabs
        connectionService.save(connection.copy(state = ConnectionStateEnum.DISCOVERING))

        return try {
            val response = httpClient.post("$o365BrowserPoolUrl/session/$clientId/rediscover")
            val responseText = response.bodyAsText()
            logger.info { "Rediscovery triggered for $clientId: $responseText" }

            BrowserSessionStatusDto(
                state = "DISCOVERING",
                message = "Znovu zjišťuji dostupné služby...",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to trigger rediscovery for $clientId" }
            // Revert to VALID on error
            connectionService.save(connection.copy(state = ConnectionStateEnum.VALID))
            BrowserSessionStatusDto(state = "ERROR", message = "Chyba: ${e.message}")
        }
    }

    /**
     * Initialize browser pool session for Teams Browser Session auth.
     * Calls POST /session/{clientId}/init on the browser pool service.
     * Returns status message (noVNC must be used to complete login).
     */
    private suspend fun initBrowserPoolSession(connection: ConnectionDocument): String {
        val clientId = connection.o365ClientId
            ?: throw IllegalArgumentException("Connection ${connection.id} has no o365ClientId")

        return try {
            val capabilities = connection.availableCapabilities.map { it.name }
            val response = httpClient.post("$o365BrowserPoolUrl/session/$clientId/init") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("login_url", "https://teams.microsoft.com")
                    putJsonArray("capabilities") { capabilities.forEach { add(it) } }
                    // Pass credentials for auto-login (username/password from connection)
                    connection.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                    connection.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                }.toString())
            }
            val responseText = response.bodyAsText()
            logger.info { "Browser pool session init for $clientId: $responseText" }

            // Parse response to check for MFA
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            val state = responseJson["state"]?.jsonPrimitive?.content ?: ""
            val message = responseJson["message"]?.jsonPrimitive?.content ?: ""

            when (state) {
                "ACTIVE" -> {
                    // Set DISCOVERING — capabilities callback from browser pool will transition to VALID
                    connectionService.save(connection.copy(state = ConnectionStateEnum.DISCOVERING))
                    "Automatické přihlášení úspěšné — zjišťuji dostupné služby..."
                }
                "AWAITING_MFA" -> {
                    connectionService.save(connection.copy(state = ConnectionStateEnum.NEW))
                    message
                }
                else -> {
                    connectionService.save(connection.copy(state = ConnectionStateEnum.NEW))
                    "Browser session inicializována pro '$clientId'. $message"
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to init browser pool session for ${connection.id}" }
            "Chyba inicializace browser session: ${e.message}"
        }
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

        // GOOGLE_WORKSPACE: list resources via Gmail/Calendar API (no ProviderRegistry)
        if (connection.provider == ProviderEnum.GOOGLE_WORKSPACE) {
            return listGoogleResources(connection, capability)
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
     * List available O365 resources via O365 Gateway (browser pool) or Graph API (OAuth2).
     * Supports CHAT_READ (teams/channels/chats), EMAIL_READ (mail folders), CALENDAR_READ (calendars).
     */
    private suspend fun listO365Resources(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        // Skip capabilities that were not discovered as available
        if (capability !in connection.availableCapabilities) {
            logger.info { "Capability $capability not available for connection ${connection.id} — skipping" }
            return emptyList()
        }

        // For OAuth2 connections, use Graph API directly with access token
        if (connection.authType == com.jervis.dto.connection.AuthTypeEnum.OAUTH2) {
            return listO365ResourcesViaGraphApi(connection, capability)
        }

        // For browser pool connections, first check persistent cache
        val clientId = connection.o365ClientId
        if (clientId.isNullOrBlank()) {
            logger.warn { "O365 connection ${connection.id} has no o365ClientId" }
            return emptyList()
        }

        // Try cached resources first (available even if browser pool is down)
        val cached = discoveredResourceRepository
            .findByConnectionIdAndActive(connection.id, true)
            .toList()

        if (cached.isNotEmpty()) {
            val freshEnough = cached.any {
                it.lastSeenAt.isAfter(java.time.Instant.now().minusSeconds(3600))
            }
            if (freshEnough) {
                return cached.mapNotNull { res ->
                    val matches = when (capability) {
                        ConnectionCapability.CHAT_READ, ConnectionCapability.CHAT_SEND ->
                            res.resourceType == "chat" || res.resourceType == "channel"
                        ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND ->
                            res.resourceType == "email" || res.resourceType == "folder"
                        ConnectionCapability.CALENDAR_READ, ConnectionCapability.CALENDAR_WRITE ->
                            res.resourceType == "calendar"
                        else -> false
                    }
                    if (matches) ConnectionResourceDto(
                        id = res.externalId,
                        name = res.displayName,
                        description = res.description,
                        capability = capability,
                    ) else null
                }
            }
        }

        // Cache miss or stale — call browser pool discovery
        return listO365ResourcesViaScraper(connection, clientId, capability)
    }

    private suspend fun listO365ResourcesViaScraper(
        connection: ConnectionDocument,
        clientId: String,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        return try {
            val resp = httpClient.post("$o365BrowserPoolUrl/scrape/$clientId/discover")
            if (!resp.status.isSuccess()) {
                logger.warn { "Browser pool discovery failed for $clientId: ${resp.status}" }
                return emptyList()
            }
            val jsonParser = Json { ignoreUnknownKeys = true }
            val body = jsonParser.parseToJsonElement(resp.bodyAsText()).jsonObject
            val resources = body["resources"]?.jsonArray ?: return emptyList()
            val result = mutableListOf<ConnectionResourceDto>()

            for (element in resources) {
                val obj = element.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val description = obj["description"]?.jsonPrimitive?.contentOrNull

                // Persist to MongoDB for cache and settings UI
                try {
                    val now = java.time.Instant.now()
                    if (!discoveredResourceRepository.existsByConnectionIdAndExternalId(connection.id, id)) {
                        discoveredResourceRepository.save(
                            com.jervis.entity.teams.O365DiscoveredResourceDocument(
                                connectionId = connection.id,
                                clientId = null,
                                resourceType = type,
                                externalId = id,
                                displayName = name,
                                description = description,
                                discoveredAt = now,
                                lastSeenAt = now,
                            )
                        )
                    }
                } catch (e: Exception) {
                    logger.debug { "Failed to persist discovered resource $id: ${e.message}" }
                }

                val matches = when (capability) {
                    ConnectionCapability.CHAT_READ, ConnectionCapability.CHAT_SEND -> type == "chat" || type == "channel"
                    ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND -> type == "email" || type == "folder"
                    ConnectionCapability.CALENDAR_READ, ConnectionCapability.CALENDAR_WRITE -> type == "calendar"
                    else -> false
                }
                if (matches) result.add(ConnectionResourceDto(id = id, name = name, description = description, capability = capability))
            }

            result
        } catch (e: Exception) {
            logger.error(e) { "Failed to discover O365 resources via scraper for $clientId" }
            emptyList()
        }
    }

    private suspend fun listO365ChatsViaGateway(
        clientId: String,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
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
            logger.error(e) { "Failed to list O365 resources via Gateway" }
            emptyList()
        }
    }

    /**
     * List O365 resources directly via Microsoft Graph API using OAuth2 access token.
     * Returns resources for the requested capability:
     * - CHAT_READ/CHAT_SEND: joined teams + channels + recent chats
     * - EMAIL_READ: mail folders
     * - CALENDAR_READ: calendars
     * - EMAIL_SEND/CALENDAR_WRITE: empty (no resource selection needed)
     */
    private suspend fun listO365ResourcesViaGraphApi(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val refreshed = refreshTokenIfNeeded(connection)
        val accessToken = refreshed.bearerToken
        if (accessToken.isNullOrBlank()) {
            logger.warn { "OAuth2 connection ${connection.id} has no access token" }
            return emptyList()
        }

        return try {
            when (capability) {
                ConnectionCapability.CHAT_READ, ConnectionCapability.CHAT_SEND ->
                    listGraphTeamsAndChats(accessToken, capability)
                ConnectionCapability.EMAIL_READ ->
                    listGraphMailFolders(accessToken)
                ConnectionCapability.EMAIL_SEND ->
                    emptyList() // Send doesn't need resource selection
                ConnectionCapability.CALENDAR_READ, ConnectionCapability.CALENDAR_WRITE ->
                    listGraphCalendars(accessToken)
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list O365 resources via Graph API for ${connection.id}: ${e.message}" }
            // Return error indicator so UI can show "not available on this account"
            listOf(
                ConnectionResourceDto(
                    id = "__error__",
                    name = "Nedostupné na tomto účtu",
                    description = "Chyba: ${e.message?.take(100)}",
                    capability = capability,
                ),
            )
        }
    }

    private suspend fun listGraphTeamsAndChats(
        accessToken: String,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val graphBaseUrl = "https://graph.microsoft.com/v1.0"
        val resources = mutableListOf<ConnectionResourceDto>()

        // List joined teams + channels
        try {
            val teamsResponse = httpClient.get("$graphBaseUrl/me/joinedTeams") {
                header("Authorization", "Bearer $accessToken")
            }
            if (teamsResponse.status.isSuccess()) {
                val teamsJson = teamsResponse.body<GraphListResponse<O365TeamDto>>()
                for (team in teamsJson.value) {
                    val teamId = team.id ?: continue
                    val teamName = team.displayName ?: "Team"

                    val channelsResponse = httpClient.get("$graphBaseUrl/teams/$teamId/channels") {
                        header("Authorization", "Bearer $accessToken")
                    }
                    if (channelsResponse.status.isSuccess()) {
                        val channelsJson = channelsResponse.body<GraphListResponse<O365ChannelDto>>()
                        for (channel in channelsJson.value) {
                            val channelId = channel.id ?: continue
                            resources.add(
                                ConnectionResourceDto(
                                    id = "$teamId/$channelId",
                                    name = "$teamName / ${channel.displayName ?: "Channel"}",
                                    description = channel.description,
                                    capability = capability,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to list Teams: ${e.message}" }
        }

        // List recent chats
        try {
            val chatsResponse = httpClient.get("$graphBaseUrl/me/chats?\$top=50&\$expand=members") {
                header("Authorization", "Bearer $accessToken")
            }
            if (chatsResponse.status.isSuccess()) {
                val chatsJson = chatsResponse.body<GraphListResponse<GraphChatDto>>()
                for (chat in chatsJson.value) {
                    val chatId = chat.id ?: continue
                    val chatName = chat.topic
                        ?: chat.members?.filter { it.displayName != null }?.joinToString(", ") { it.displayName!! }
                        ?: "Chat"
                    resources.add(
                        ConnectionResourceDto(
                            id = "chat:$chatId",
                            name = chatName,
                            description = chat.chatType,
                            capability = capability,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to list Chats: ${e.message}" }
        }

        return resources
    }

    private suspend fun listGraphMailFolders(accessToken: String): List<ConnectionResourceDto> {
        val graphBaseUrl = "https://graph.microsoft.com/v1.0"
        val response = httpClient.get("$graphBaseUrl/me/mailFolders?\$top=50") {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Mail folders API returned ${response.status}")
        }

        val foldersJson = response.body<GraphListResponse<GraphMailFolderDto>>()
        return foldersJson.value.map { folder ->
            ConnectionResourceDto(
                id = folder.id ?: "",
                name = "${folder.displayName ?: "Folder"} (${folder.totalItemCount ?: 0})",
                description = "${folder.unreadItemCount ?: 0} nepřečtených",
                capability = ConnectionCapability.EMAIL_READ,
            )
        }
    }

    private suspend fun listGraphCalendars(accessToken: String): List<ConnectionResourceDto> {
        val graphBaseUrl = "https://graph.microsoft.com/v1.0"
        val response = httpClient.get("$graphBaseUrl/me/calendars") {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Calendars API returned ${response.status}")
        }

        val calendarsJson = response.body<GraphListResponse<GraphCalendarDto>>()
        return calendarsJson.value.map { calendar ->
            ConnectionResourceDto(
                id = calendar.id ?: "",
                name = calendar.name ?: "Calendar",
                description = if (calendar.isDefaultCalendar == true) "Výchozí kalendář" else null,
                capability = ConnectionCapability.CALENDAR_READ,
            )
        }
    }

    // ─── Google Workspace (Gmail API + Google Calendar API) ───

    private suspend fun listGoogleResources(
        connection: ConnectionDocument,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto> {
        val refreshed = refreshTokenIfNeeded(connection)
        val accessToken = refreshed.bearerToken
        if (accessToken.isNullOrBlank()) {
            logger.warn { "Google OAuth2 connection ${connection.id} has no access token" }
            return emptyList()
        }

        return try {
            when (capability) {
                ConnectionCapability.EMAIL_READ ->
                    listGmailLabels(accessToken)
                ConnectionCapability.EMAIL_SEND ->
                    emptyList() // Send doesn't need resource selection
                ConnectionCapability.CALENDAR_READ, ConnectionCapability.CALENDAR_WRITE ->
                    listGoogleCalendars(accessToken)
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Google resources for ${connection.id}: ${e.message}" }
            listOf(
                ConnectionResourceDto(
                    id = "__error__",
                    name = "Nedostupné na tomto účtu",
                    description = "Chyba: ${e.message?.take(100)}",
                    capability = capability,
                ),
            )
        }
    }

    private suspend fun listGmailLabels(accessToken: String): List<ConnectionResourceDto> {
        val response = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/labels") {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Gmail labels API returned ${response.status}")
        }

        val body = response.body<GmailLabelsResponse>()
        // Show only user-visible labels (skip CATEGORY_*, UNREAD, STARRED, etc.)
        val visibleTypes = setOf("system", "user")
        return body.labels
            .filter { it.type in visibleTypes }
            .filter { label ->
                // Skip internal system labels that aren't useful as mail folders
                val id = label.id ?: ""
                !id.startsWith("CATEGORY_") && id !in setOf("UNREAD", "STARRED", "IMPORTANT", "CHAT", "SPAM", "TRASH")
            }
            .sortedWith(compareBy({ it.type != "system" }, { it.name }))
            .map { label ->
                ConnectionResourceDto(
                    id = label.id ?: "",
                    name = label.name ?: "Label",
                    description = "Gmail label: ${label.id}",
                    capability = ConnectionCapability.EMAIL_READ,
                )
            }
    }

    private suspend fun listGoogleCalendars(accessToken: String): List<ConnectionResourceDto> {
        val response = httpClient.get("https://www.googleapis.com/calendar/v3/users/me/calendarList") {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Google Calendar API returned ${response.status}")
        }

        val body = response.body<GoogleCalendarListResponse>()
        return body.items.map { calendar ->
            ConnectionResourceDto(
                id = calendar.id ?: "",
                name = calendar.summary ?: "Calendar",
                description = if (calendar.primary == true) "Výchozí kalendář" else calendar.description,
                capability = ConnectionCapability.CALENDAR_READ,
            )
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
    private data class GraphListResponse<T>(
        val value: List<T> = emptyList(),
        @kotlinx.serialization.SerialName("@odata.nextLink")
        val nextLink: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class GraphChatDto(
        val id: String? = null,
        val topic: String? = null,
        val chatType: String? = null,
        val members: List<GraphChatMemberDto>? = null,
    )

    @kotlinx.serialization.Serializable
    private data class GraphChatMemberDto(
        val displayName: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class GraphMailFolderDto(
        val id: String? = null,
        val displayName: String? = null,
        val totalItemCount: Int? = null,
        val unreadItemCount: Int? = null,
    )

    @kotlinx.serialization.Serializable
    private data class GraphCalendarDto(
        val id: String? = null,
        val name: String? = null,
        val isDefaultCalendar: Boolean? = null,
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

    // ─── Gmail API DTOs ───

    @kotlinx.serialization.Serializable
    private data class GmailLabelsResponse(
        val labels: List<GmailLabelDto> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    private data class GmailLabelDto(
        val id: String? = null,
        val name: String? = null,
        val type: String? = null, // "system" or "user"
    )

    // ─── Google Calendar API DTOs ───

    @kotlinx.serialization.Serializable
    private data class GoogleCalendarListResponse(
        val items: List<GoogleCalendarEntryDto> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    private data class GoogleCalendarEntryDto(
        val id: String? = null,
        val summary: String? = null,
        val description: String? = null,
        val primary: Boolean? = null,
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
        selfUsername = selfUsername,
        selfDisplayName = selfDisplayName,
        selfId = selfId,
        selfEmail = selfEmail,
        isJervisOwned = isJervisOwned,
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
