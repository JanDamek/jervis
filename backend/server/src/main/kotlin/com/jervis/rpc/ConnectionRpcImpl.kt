package com.jervis.rpc

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.bugtracker.BugTrackerUserRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderCapabilities
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.connection.RateLimitConfigDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.IConnectionService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.oauth2.OAuth2Service
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import jakarta.mail.Authenticator
import jakarta.mail.Folder
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.Properties

/**
 * REST controller for managing connections.
 *
 * Architecture:
 * - provider: WHERE we connect (GitHub, GitLab, Atlassian, etc.)
 * - protocol: HOW we communicate (HTTP, IMAP, POP3, SMTP)
 * - authType: HOW we authenticate (NONE, BASIC, BEARER, OAUTH2)
 * - capabilities: WHAT the connection can do (derived from provider/protocol)
 */
@Component
class ConnectionRpcImpl(
    private val connectionService: ConnectionService,
    private val httpClient: HttpClient,
    private val bugTrackerClient: IBugTrackerClient,
    private val wikiClient: IWikiClient,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
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
        connectionService
            .findAll()
            .map { it.toDto() }
            .toList()

    override suspend fun getConnectionById(id: String): ConnectionResponseDto? =
        connectionService.findById(ConnectionId.fromString(id))?.toDto()

    override suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto {
        val provider = request.provider
        val protocol = request.protocol
        val authType = request.authType

        // Derive capabilities from provider and protocol
        val capabilities = ProviderCapabilities.forProviderAndProtocol(provider, protocol)

        val connectionDocument = ConnectionDocument(
            name = request.name,
            provider = provider,
            protocol = protocol,
            authType = authType,
            state = request.state,
            availableCapabilities = capabilities,

            // HTTP/API configuration
            baseUrl = request.baseUrl ?: "",
            timeoutMs = request.timeoutMs ?: 30000,

            // Authentication credentials
            username = request.username ?: request.httpBasicUsername,
            password = request.password ?: request.httpBasicPassword,
            bearerToken = request.bearerToken ?: request.httpBearerToken,

            // OAuth2 specific
            authorizationUrl = request.authorizationUrl,
            tokenUrl = request.tokenUrl,
            clientSecret = request.clientSecret,
            scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            redirectUri = request.redirectUri,

            // Email configuration
            host = request.host,
            port = request.port ?: getDefaultPort(protocol),
            useSsl = request.useSsl ?: true,
            useTls = request.useTls,
            folderName = request.folderName ?: "INBOX",

            // Rate limiting
            rateLimitConfig = request.rateLimitConfig?.toEntity()
                ?: ConnectionDocument.RateLimitConfig(10, 100),

            // Provider-specific identifiers
            jiraProjectKey = request.jiraProjectKey,
            confluenceSpaceKey = request.confluenceSpaceKey,
            confluenceRootPageId = request.confluenceRootPageId,
            bitbucketRepoSlug = request.bitbucketRepoSlug,
            gitRemoteUrl = request.gitRemoteUrl,
        )

        val created = connectionService.save(connectionDocument)
        logger.info { "Created connection: ${created.name} (${created.id}) - provider=${provider}, protocol=${protocol}, authType=${authType}" }
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
        val newProtocol = request.protocol ?: existing.protocol
        val newAuthType = request.authType ?: existing.authType

        // Recalculate capabilities if provider or protocol changed
        val capabilities = if (request.provider != null || request.protocol != null) {
            ProviderCapabilities.forProviderAndProtocol(newProvider, newProtocol)
        } else {
            existing.availableCapabilities
        }

        val updated = existing.copy(
            name = request.name ?: existing.name,
            provider = newProvider,
            protocol = newProtocol,
            authType = newAuthType,
            availableCapabilities = capabilities,

            // HTTP/API configuration
            baseUrl = request.baseUrl ?: existing.baseUrl,
            timeoutMs = request.timeoutMs ?: existing.timeoutMs,

            // Authentication credentials
            username = request.username ?: request.httpBasicUsername ?: existing.username,
            password = request.password ?: request.httpBasicPassword ?: existing.password,
            bearerToken = request.bearerToken ?: request.httpBearerToken ?: existing.bearerToken,

            // OAuth2 specific
            authorizationUrl = request.authorizationUrl ?: existing.authorizationUrl,
            tokenUrl = request.tokenUrl ?: existing.tokenUrl,
            clientSecret = request.clientSecret ?: existing.clientSecret,
            scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: existing.scopes,
            redirectUri = request.redirectUri ?: existing.redirectUri,

            // Email configuration
            host = request.host ?: existing.host,
            port = request.port ?: existing.port,
            useSsl = request.useSsl ?: existing.useSsl,
            useTls = request.useTls ?: existing.useTls,
            folderName = request.folderName ?: existing.folderName,

            // Rate limiting
            rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,

            // Provider-specific identifiers
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

    override suspend fun listImportableProjects(connectionId: String): List<com.jervis.dto.connection.ConnectionImportProjectDto> {
        // TODO: Implement project import from GitHub/GitLab/etc
        logger.warn { "listImportableProjects not yet implemented for connection $connectionId" }
        return emptyList()
    }

    override suspend fun importProject(
        connectionId: String,
        externalId: String,
    ): com.jervis.dto.ProjectDto {
        // TODO: Implement project import from GitHub/GitLab/etc
        throw UnsupportedOperationException("importProject not yet implemented")
    }

    override suspend fun testConnection(id: String): ConnectionTestResultDto {
        val connection =
            connectionService.findById(ConnectionId.fromString(id))
                ?: throw IllegalArgumentException("Connection not found: $id")

        return try {
            when (connection.protocol) {
                ProtocolEnum.HTTP -> testHttpConnection(connection)
                ProtocolEnum.IMAP -> testImapConnection(connection)
                ProtocolEnum.POP3 -> testPop3Connection(connection)
                ProtocolEnum.SMTP -> testSmtpConnection(connection)
            }
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
            )
        }
    }

    override suspend fun initiateOAuth2(connectionId: String): String {
        val connection =
            connectionService.findById(ConnectionId.fromString(connectionId))
                ?: throw IllegalArgumentException("Connection not found: $connectionId")

        // Generate authorization URL using OAuth2Service
        val response = oauth2Service.getAuthorizationUrl(ConnectionId.fromString(connectionId))
        return response.authorizationUrl
    }

    override suspend fun listAvailableResources(
        connectionId: String,
        capability: ConnectionCapability,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        val connection =
            connectionService.findById(ConnectionId.fromString(connectionId))
                ?: throw IllegalArgumentException("Connection not found: $connectionId")

        return when (capability) {
            ConnectionCapability.BUGTRACKER -> listBugtrackerProjects(connection)
            ConnectionCapability.WIKI -> listWikiSpaces(connection)
            ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND -> listEmailFolders(connection)
            ConnectionCapability.REPOSITORY -> listRepositories(connection)
        }
    }

    private suspend fun listBugtrackerProjects(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return try {
            val response = withRpcRetry(
                name = "BugTracker",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                bugTrackerClient.listProjects(
                    com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest(
                        baseUrl = connection.baseUrl,
                        authType = connection.authType.name,
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
                    ),
                )
            }
            response.projects.map { project ->
                com.jervis.dto.connection.ConnectionResourceDto(
                    id = project.key,
                    name = project.name,
                    description = project.description,
                    capability = ConnectionCapability.BUGTRACKER,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list bugtracker projects for connection ${connection.id}" }
            emptyList()
        }
    }

    private suspend fun listWikiSpaces(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return try {
            val response = withRpcRetry(
                name = "Wiki",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                wikiClient.listSpaces(
                    com.jervis.common.dto.wiki.WikiSpacesRequest(
                        baseUrl = connection.baseUrl,
                        authType = connection.authType.name,
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
                    ),
                )
            }
            response.spaces.map { space ->
                com.jervis.dto.connection.ConnectionResourceDto(
                    id = space.key,
                    name = space.name,
                    description = space.description,
                    capability = ConnectionCapability.WIKI,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list wiki spaces for connection ${connection.id}" }
            emptyList()
        }
    }

    private suspend fun listEmailFolders(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return withContext(Dispatchers.IO) {
            try {
                val protocol = if (connection.protocol == ProtocolEnum.IMAP) "imap" else "pop3"
                val properties = Properties().apply {
                    setProperty("mail.store.protocol", protocol)
                    setProperty("mail.$protocol.host", connection.host ?: "")
                    setProperty("mail.$protocol.port", connection.port.toString())
                    if (connection.useSsl) {
                        setProperty("mail.$protocol.ssl.enable", "true")
                        setProperty("mail.$protocol.ssl.trust", "*")
                    }
                    setProperty("mail.$protocol.connectiontimeout", "10000")
                    setProperty("mail.$protocol.timeout", "10000")
                }

                val session = Session.getInstance(properties)
                val store = session.getStore(protocol)
                store.connect(
                    connection.host,
                    connection.port,
                    connection.username,
                    connection.password,
                )

                val folders = mutableListOf<com.jervis.dto.connection.ConnectionResourceDto>()
                val defaultFolder = store.defaultFolder
                listFoldersRecursively(defaultFolder, folders)

                store.close()
                folders
            } catch (e: Exception) {
                logger.error(e) { "Failed to list email folders for connection ${connection.id}" }
                emptyList()
            }
        }
    }

    private fun listFoldersRecursively(
        folder: Folder,
        result: MutableList<com.jervis.dto.connection.ConnectionResourceDto>,
    ) {
        val subfolders = folder.list()
        for (subfolder in subfolders) {
            result.add(
                com.jervis.dto.connection.ConnectionResourceDto(
                    id = subfolder.fullName,
                    name = subfolder.name,
                    description = "Email folder: ${subfolder.fullName}",
                    capability = ConnectionCapability.EMAIL_READ,
                ),
            )
            if ((subfolder.type and Folder.HOLDS_FOLDERS) != 0) {
                listFoldersRecursively(subfolder, result)
            }
        }
    }

    private suspend fun listRepositories(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return try {
            when (connection.provider) {
                ProviderEnum.GITHUB -> listGitHubRepositories(connection)
                ProviderEnum.GITLAB -> listGitLabRepositories(connection)
                ProviderEnum.ATLASSIAN -> listBitbucketRepositories(connection)
                else -> {
                    logger.warn { "Provider ${connection.provider} does not support repository listing" }
                    emptyList()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list repositories for connection ${connection.id}" }
            emptyList()
        }
    }

    private suspend fun listGitHubRepositories(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        val response = httpClient.get("https://api.github.com/user/repos?per_page=100") {
            connection.toAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val json = Json { ignoreUnknownKeys = true }
        val repos = json.decodeFromString<List<GitHubRepoResponse>>(response.body<String>())
        return repos.map { repo ->
            com.jervis.dto.connection.ConnectionResourceDto(
                id = repo.full_name,
                name = repo.name,
                description = repo.description,
                capability = ConnectionCapability.REPOSITORY,
            )
        }
    }

    @Serializable
    private data class GitHubRepoResponse(
        val id: Long,
        val name: String,
        val full_name: String,
        val description: String? = null,
    )

    private suspend fun listGitLabRepositories(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        val baseUrl = connection.baseUrl.removeSuffix("/")
        val response = httpClient.get("$baseUrl/api/v4/projects?membership=true&per_page=100") {
            connection.toAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val json = Json { ignoreUnknownKeys = true }
        val repos = json.decodeFromString<List<GitLabRepoResponse>>(response.body<String>())
        return repos.map { repo ->
            com.jervis.dto.connection.ConnectionResourceDto(
                id = repo.path_with_namespace,
                name = repo.name,
                description = repo.description,
                capability = ConnectionCapability.REPOSITORY,
            )
        }
    }

    @Serializable
    private data class GitLabRepoResponse(
        val id: Long,
        val name: String,
        val path_with_namespace: String,
        val description: String? = null,
    )

    private suspend fun listBitbucketRepositories(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        // TODO: Implement Bitbucket repository listing
        logger.warn { "Bitbucket repository listing not yet implemented" }
        return emptyList()
    }

    private suspend fun testHttpConnection(connection: ConnectionDocument): ConnectionTestResultDto =
        try {
            // Test based on provider
            when (connection.provider) {
                ProviderEnum.ATLASSIAN -> testAtlassianConnection(connection)
                ProviderEnum.GITHUB -> testGitHubConnection(connection)
                ProviderEnum.GITLAB -> testGitLabConnection(connection)
                ProviderEnum.AZURE_DEVOPS -> testAzureDevOpsConnection(connection)
                ProviderEnum.GOOGLE_CLOUD -> testGoogleCloudConnection(connection)
                else -> testGenericHttpConnection(connection)
            }
        } catch (e: Exception) {
            logger.error(e) { "HTTP connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
                details = mapOf(
                    "error" to (e.message ?: "Unknown error"),
                    "errorType" to e::class.simpleName!!,
                ),
            )
        }

    private suspend fun testAtlassianConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val myself = withRpcRetry(
            name = "Atlassian",
            reconnect = { reconnectHandler.reconnectAtlassian() },
        ) {
            bugTrackerClient.getUser(connection.toBugTrackerUserRequest())
        }
        return ConnectionTestResultDto(
            success = true,
            message = "Connection successful! Logged in as: ${myself.displayName}",
            details = mapOf(
                "id" to myself.id,
                "email" to (myself.email ?: "N/A"),
                "displayName" to myself.displayName,
            ),
        )
    }

    private suspend fun testGitHubConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val response = httpClient.get("https://api.github.com/user") {
            connection.toAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }

        if (response.status.isSuccess()) {
            val json = Json { ignoreUnknownKeys = true }
            val userResponse = json.decodeFromString(GitHubUserResponse.serializer(), response.body<String>())
            return ConnectionTestResultDto(
                success = true,
                message = "GitHub connection successful! User: ${userResponse.login}",
                details = mapOf(
                    "login" to userResponse.login,
                    "id" to userResponse.id.toString(),
                    "url" to "https://api.github.com/user",
                ),
            )
        } else {
            return ConnectionTestResultDto(
                success = false,
                message = "GitHub connection failed! Status: ${response.status}",
                details = mapOf(
                    "status" to response.status.toString(),
                    "url" to "https://api.github.com/user",
                ),
            )
        }
    }

    private suspend fun testGitLabConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val baseUrl = connection.baseUrl.removeSuffix("/")
        val response = httpClient.get("$baseUrl/api/v4/user") {
            connection.toAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }

        if (response.status.isSuccess()) {
            val json = Json { ignoreUnknownKeys = true }
            val userResponse = json.decodeFromString(GitLabUserResponse.serializer(), response.body<String>())
            return ConnectionTestResultDto(
                success = true,
                message = "GitLab connection successful! User: ${userResponse.username}",
                details = mapOf(
                    "username" to userResponse.username,
                    "id" to userResponse.id.toString(),
                    "url" to "$baseUrl/api/v4/user",
                ),
            )
        } else {
            return ConnectionTestResultDto(
                success = false,
                message = "GitLab connection failed! Status: ${response.status}",
                details = mapOf(
                    "status" to response.status.toString(),
                    "url" to "$baseUrl/api/v4/user",
                ),
            )
        }
    }

    private suspend fun testAzureDevOpsConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        // TODO: Implement Azure DevOps connection test
        return ConnectionTestResultDto(
            success = true,
            message = "Azure DevOps connection test not yet implemented",
        )
    }

    private suspend fun testGoogleCloudConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        // TODO: Implement Google Cloud connection test
        return ConnectionTestResultDto(
            success = true,
            message = "Google Cloud connection test not yet implemented",
        )
    }

    private suspend fun testGenericHttpConnection(connection: ConnectionDocument): ConnectionTestResultDto {
        val response = httpClient.get(connection.baseUrl) {
            connection.toAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }

        return ConnectionTestResultDto(
            success = response.status.isSuccess(),
            message = if (response.status.isSuccess()) {
                "Connection successful! Status: ${response.status}"
            } else {
                "Connection failed! Status: ${response.status}"
            },
            details = mapOf(
                "status" to response.status.toString(),
                "url" to connection.baseUrl,
            ),
        )
    }

    @Serializable
    private data class GitHubUserResponse(
        val login: String,
        val id: Long,
    )

    @Serializable
    private data class GitLabUserResponse(
        val username: String,
        val id: Long,
    )

    private fun ConnectionDocument.toBugTrackerUserRequest(): BugTrackerUserRequest =
        BugTrackerUserRequest(
            baseUrl = this.baseUrl,
            authType = this.authType.name,
            basicUsername = this.username,
            basicPassword = this.password,
            bearerToken = this.bearerToken,
        )

    private suspend fun testImapConnection(connection: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties = Properties().apply {
                    setProperty("mail.store.protocol", "imap")
                    setProperty("mail.imap.host", connection.host ?: "")
                    setProperty("mail.imap.port", connection.port.toString())
                    if (connection.useSsl) {
                        setProperty("mail.imap.ssl.enable", "true")
                        setProperty("mail.imap.ssl.trust", "*")
                    }
                    setProperty("mail.imap.connectiontimeout", "10000")
                    setProperty("mail.imap.timeout", "10000")
                }

                val session = Session.getInstance(properties)
                val store = session.getStore("imap")

                store.connect(
                    connection.host,
                    connection.port,
                    connection.username,
                    connection.password,
                )

                val folder = store.getFolder(connection.folderName)
                folder.open(Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "IMAP connection successful! Found $messageCount messages in ${connection.folderName}",
                    details = listOfNotNull(
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                        connection.username?.let { "username" to it },
                        "folder" to connection.folderName,
                        "messageCount" to messageCount.toString(),
                    ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connection failed: ${e.message}",
                    details = listOfNotNull(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                    ).toMap(),
                )
            }
        }

    private suspend fun testPop3Connection(connection: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties = Properties().apply {
                    setProperty("mail.store.protocol", "pop3")
                    setProperty("mail.pop3.host", connection.host ?: "")
                    setProperty("mail.pop3.port", connection.port.toString())
                    if (connection.useSsl) {
                        setProperty("mail.pop3.ssl.enable", "true")
                        setProperty("mail.pop3.ssl.trust", "*")
                    }
                    setProperty("mail.pop3.connectiontimeout", "10000")
                    setProperty("mail.pop3.timeout", "10000")
                }

                val session = Session.getInstance(properties)
                val store = session.getStore("pop3")

                store.connect(
                    connection.host,
                    connection.port,
                    connection.username,
                    connection.password,
                )

                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "POP3 connection successful! Found $messageCount messages",
                    details = listOfNotNull(
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                        connection.username?.let { "username" to it },
                        "messageCount" to messageCount.toString(),
                    ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connection failed: ${e.message}",
                    details = listOfNotNull(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                    ).toMap(),
                )
            }
        }

    private suspend fun testSmtpConnection(connection: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties = Properties().apply {
                    setProperty("mail.smtp.host", connection.host ?: "")
                    setProperty("mail.smtp.port", connection.port.toString())
                    setProperty("mail.smtp.auth", "true")
                    if (connection.useTls == true) {
                        setProperty("mail.smtp.starttls.enable", "true")
                        setProperty("mail.smtp.ssl.trust", "*")
                    }
                    setProperty("mail.smtp.connectiontimeout", "10000")
                    setProperty("mail.smtp.timeout", "10000")
                }

                val session = Session.getInstance(
                    properties,
                    object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication =
                            PasswordAuthentication(
                                connection.username,
                                connection.password,
                            )
                    },
                )

                val transport = session.getTransport("smtp")
                transport.connect(
                    connection.host,
                    connection.port,
                    connection.username,
                    connection.password,
                )
                transport.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "SMTP connection successful! Server is ready to send emails",
                    details = listOfNotNull(
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                        connection.username?.let { "username" to it },
                        "tls" to connection.useTls.toString(),
                    ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connection failed: ${e.message}",
                    details = listOfNotNull(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        connection.host?.let { "host" to it },
                        "port" to connection.port.toString(),
                    ).toMap(),
                )
            }
        }

    /**
     * Get default port for protocol.
     */
    private fun getDefaultPort(protocol: ProtocolEnum): Int = when (protocol) {
        ProtocolEnum.HTTP -> 443
        ProtocolEnum.IMAP -> 993
        ProtocolEnum.POP3 -> 995
        ProtocolEnum.SMTP -> 587
    }
}

/**
 * Extension to convert ConnectionDocument to DTO.
 */
private fun ConnectionDocument.toDto(): ConnectionResponseDto =
    ConnectionResponseDto(
        id = id.toString(),
        provider = provider,
        protocol = protocol,
        authType = authType,
        name = name,
        state = state,
        capabilities = getCapabilities(),

        // HTTP/API configuration
        baseUrl = baseUrl,
        timeoutMs = timeoutMs,

        // Authentication credentials
        username = username,
        password = password,
        bearerToken = bearerToken,

        // OAuth2 specific
        authorizationUrl = authorizationUrl,
        tokenUrl = tokenUrl,
        clientSecret = clientSecret,
        redirectUri = redirectUri,
        scope = scopes.joinToString(" "),

        // Email configuration
        host = host,
        port = port,
        useSsl = useSsl,
        useTls = useTls,
        folderName = folderName,

        // Rate limiting
        rateLimitConfig = rateLimitConfig.toDto(),

        // Provider-specific identifiers
        jiraProjectKey = jiraProjectKey,
        confluenceSpaceKey = confluenceSpaceKey,
        confluenceRootPageId = confluenceRootPageId,
        bitbucketRepoSlug = bitbucketRepoSlug,
        gitRemoteUrl = gitRemoteUrl,

        // Legacy compatibility
        hasCredentials = username != null || bearerToken != null,
        type = protocol,
        httpBearerToken = bearerToken,
        httpBasicUsername = username,
        httpBasicPassword = password,
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
