package com.jervis.rpc

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.bugtracker.BugTrackerUserRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionTypeEnum
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.HttpAuthTypeEnum
import com.jervis.dto.connection.RateLimitConfigDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
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
 * Provides CRUD operations and testing functionality for all connection types.
 * UI uses this to configure connections from first application start.
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
        val connectionDocument: ConnectionDocument =
            when (request.type) {
                ConnectionTypeEnum.HTTP -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.HTTP
                    ConnectionDocument(
                        name = request.name,
                        provider = request.provider,
                        baseUrl = request.baseUrl ?: error("baseUrl required for HTTP connection"),
                        credentials =
                            mapHttpCredentials(
                                authType = request.authType,
                                basicUser = request.httpBasicUsername,
                                basicPass = request.httpBasicPassword,
                                bearer = request.httpBearerToken,
                            ),
                        timeoutMs = request.timeoutMs ?: 30000,
                        connectionType = type,
                        availableCapabilities = detectCapabilities(type, request.baseUrl),
                        rateLimitConfig =
                            request.rateLimitConfig?.toEntity() ?: ConnectionDocument.RateLimitConfig(
                                10,
                                100,
                            ),
                        jiraProjectKey = request.jiraProjectKey,
                        confluenceSpaceKey = request.confluenceSpaceKey,
                        confluenceRootPageId = request.confluenceRootPageId,
                        bitbucketRepoSlug = request.bitbucketRepoSlug,
                    )
                }

                ConnectionTypeEnum.IMAP -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.IMAP
                    ConnectionDocument(
                        name = request.name,
                        provider = request.provider,
                        host = request.host ?: error("host required for IMAP connection"),
                        port = request.port ?: 993,
                        username = request.username ?: error("username required for IMAP connection"),
                        password = request.password ?: error("password required for IMAP connection"),
                        useSsl = request.useSsl ?: true,
                        folderName = request.folderName ?: "INBOX",
                        connectionType = type,
                        availableCapabilities = detectCapabilities(type, request.host),
                        rateLimitConfig =
                            request.rateLimitConfig?.toEntity() ?: ConnectionDocument.RateLimitConfig(
                                10,
                                100,
                            ),
                    )
                }

                ConnectionTypeEnum.POP3 -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.POP3
                    ConnectionDocument(
                        name = request.name,
                        provider = request.provider,
                        host = request.host ?: error("host required for POP3 connection"),
                        port = request.port ?: 995,
                        username = request.username ?: error("username required for POP3 connection"),
                        password = request.password ?: error("password required for POP3 connection"),
                        useSsl = request.useSsl ?: true,
                        connectionType = type,
                        availableCapabilities = detectCapabilities(type, request.host),
                        rateLimitConfig =
                            request.rateLimitConfig?.toEntity() ?: ConnectionDocument.RateLimitConfig(
                                10,
                                100,
                            ),
                    )
                }

                ConnectionTypeEnum.SMTP -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.SMTP
                    ConnectionDocument(
                        name = request.name,
                        provider = request.provider,
                        host = request.host ?: error("host required for SMTP connection"),
                        port = request.port ?: 587,
                        username = request.username ?: error("username required for SMTP connection"),
                        password = request.password ?: error("password required for SMTP connection"),
                        useTls = request.useTls ?: true,
                        connectionType = type,
                        availableCapabilities = detectCapabilities(type, request.host),
                        rateLimitConfig =
                            request.rateLimitConfig?.toEntity() ?: ConnectionDocument.RateLimitConfig(
                                10,
                                100,
                            ),
                    )
                }

                ConnectionTypeEnum.OAUTH2 -> {
                    val effectiveBaseUrl =
                        if (request.isCloud) {
                            ""
                        } else {
                            (
                                request.baseUrl
                                    ?: error("baseUrl required for on-premise OAuth2 connection")
                            )
                        }
                    ConnectionDocument(
                        name = request.name,
                        provider = request.provider,
                        baseUrl = effectiveBaseUrl,
                        authorizationUrl = request.authorizationUrl,
                        tokenUrl = request.tokenUrl,
                        clientSecret = request.clientSecret,
                        scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                        redirectUri = request.redirectUri,
                        connectionType = ConnectionDocument.ConnectionTypeEnum.OAUTH2,
                        rateLimitConfig =
                            request.rateLimitConfig?.toEntity() ?: ConnectionDocument.RateLimitConfig(
                                10,
                                100,
                            ),
                        jiraProjectKey = request.jiraProjectKey,
                        confluenceSpaceKey = request.confluenceSpaceKey,
                        confluenceRootPageId = request.confluenceRootPageId,
                        bitbucketRepoSlug = request.bitbucketRepoSlug,
                        gitProvider =
                            request.gitProvider?.let {
                                try {
                                    com.jervis.domain.git.GitProviderEnum
                                        .valueOf(it.uppercase())
                                } catch (e: IllegalArgumentException) {
                                    null
                                }
                            },
                    )
                }

                // Note: GIT type is not in DTO ConnectionTypeEnum, so this case is handled via HTTP connections with GitHub/GitLab providers

                else -> {
                    error("Unknown connection type: ${request.type}")
                }
            }

        val created = connectionService.save(connectionDocument)
        logger.info { "Created connection: ${created.name} (${created.id})" }
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

        val updated: ConnectionDocument =
            when (existing.connectionType) {
                ConnectionDocument.ConnectionTypeEnum.HTTP -> {
                    val updatedCreds =
                        when {
                            // If explicit authType provided, derive from typed fields
                            request.authType != null -> {
                                mapHttpCredentials(
                                    authType = request.authType,
                                    basicUser = request.httpBasicUsername,
                                    basicPass = request.httpBasicPassword,
                                    bearer = request.httpBearerToken,
                                )
                            }

                            // If any typed field provided without authType, infer
                            listOf(
                                request.httpBasicUsername,
                                request.httpBasicPassword,
                                request.httpBearerToken,
                            ).any { it != null } -> {
                                mapHttpCredentials(
                                    authType =
                                        inferAuthType(
                                            request.httpBasicUsername,
                                            request.httpBasicPassword,
                                            request.httpBearerToken,
                                        ),
                                    basicUser = request.httpBasicUsername,
                                    basicPass = request.httpBasicPassword,
                                    bearer = request.httpBearerToken,
                                )
                            }

                            else -> {
                                existing.credentials
                            }
                        }

                    val newBaseUrl = request.baseUrl ?: existing.baseUrl
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        baseUrl = newBaseUrl,
                        credentials = updatedCreds,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = existing.state,
                        availableCapabilities = detectCapabilities(existing.connectionType, newBaseUrl),
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                        jiraProjectKey = request.jiraProjectKey ?: existing.jiraProjectKey,
                        confluenceSpaceKey = request.confluenceSpaceKey ?: existing.confluenceSpaceKey,
                        confluenceRootPageId = request.confluenceRootPageId ?: existing.confluenceRootPageId,
                        bitbucketRepoSlug = request.bitbucketRepoSlug ?: existing.bitbucketRepoSlug,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.IMAP -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useSsl = request.useSsl ?: existing.useSsl,
                        folderName = request.folderName ?: existing.folderName,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.POP3 -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useSsl = request.useSsl ?: existing.useSsl,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.SMTP -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useTls = request.useTls ?: existing.useTls,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
                    val baseUrl =
                        if (request.isCloud == true) {
                            ""
                        } else {
                            request.baseUrl ?: existing.baseUrl
                        }
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        baseUrl = baseUrl,
                        authorizationUrl = request.authorizationUrl ?: existing.authorizationUrl,
                        tokenUrl = request.tokenUrl ?: existing.tokenUrl,
                        clientSecret = request.clientSecret ?: existing.clientSecret,
                        redirectUri = request.redirectUri ?: existing.redirectUri,
                        scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: existing.scopes,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                        jiraProjectKey = request.jiraProjectKey ?: existing.jiraProjectKey,
                        confluenceSpaceKey = request.confluenceSpaceKey ?: existing.confluenceSpaceKey,
                        confluenceRootPageId = request.confluenceRootPageId ?: existing.confluenceRootPageId,
                        bitbucketRepoSlug = request.bitbucketRepoSlug ?: existing.bitbucketRepoSlug,
                        gitProvider =
                            request.gitProvider?.let {
                                try {
                                    com.jervis.domain.git.GitProviderEnum
                                        .valueOf(it.uppercase())
                                } catch (e: IllegalArgumentException) {
                                    existing.gitProvider
                                }
                            } ?: existing.gitProvider,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.GIT -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        provider = request.provider ?: existing.provider,
                        state = existing.state,
                        gitRemoteUrl = request.gitRemoteUrl ?: existing.gitRemoteUrl,
                        gitProvider =
                            request.gitProvider?.let {
                                com.jervis.domain.git.GitProviderEnum
                                    .valueOf(it.uppercase())
                            }
                                ?: existing.gitProvider,
                    )
                }
            }

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
        val connectionDocument =
            connectionService.findById(ConnectionId.fromString(id))
                ?: throw IllegalArgumentException("ConnectionDocument not found: $id")

        return try {
            // Test connection based on type
            when (connectionDocument.connectionType) {
                ConnectionDocument.ConnectionTypeEnum.HTTP -> {
                    testHttpConnection(connectionDocument)
                }

                ConnectionDocument.ConnectionTypeEnum.IMAP -> {
                    testImapConnection(connectionDocument)
                }

                ConnectionDocument.ConnectionTypeEnum.POP3 -> {
                    testPop3Connection(connectionDocument)
                }

                ConnectionDocument.ConnectionTypeEnum.SMTP -> {
                    testSmtpConnection(connectionDocument)
                }

                ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
                    ConnectionTestResultDto(
                        success = true,
                        message = "OAuth2 test not yet implemented",
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.GIT -> {
                    ConnectionTestResultDto(
                        success = true,
                        message = "GIT test not yet implemented",
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "ConnectionDocument test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "ConnectionDocument test failed: ${e.message}",
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
            ConnectionCapability.EMAIL -> listEmailFolders(connection)
            ConnectionCapability.REPOSITORY, ConnectionCapability.GIT -> listRepositories(connection)
        }
    }

    private suspend fun listBugtrackerProjects(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return try {
            // Use bug tracker client to list projects
            val response = withRpcRetry(
                name = "BugTracker",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                bugTrackerClient.listProjects(
                    com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest(
                        baseUrl = connection.baseUrl,
                        authType = when (connection.credentials) {
                            is HttpCredentials.Basic -> "BASIC"
                            is HttpCredentials.Bearer -> "BEARER"
                            null -> "NONE"
                        },
                        basicUsername = (connection.credentials as? HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? HttpCredentials.Bearer)?.token,
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
            // Use wiki client to list spaces
            val response = withRpcRetry(
                name = "Wiki",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                wikiClient.listSpaces(
                    com.jervis.common.dto.wiki.WikiSpacesRequest(
                        baseUrl = connection.baseUrl,
                        authType = when (connection.credentials) {
                            is HttpCredentials.Basic -> "BASIC"
                            is HttpCredentials.Bearer -> "BEARER"
                            null -> "NONE"
                        },
                        basicUsername = (connection.credentials as? HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? HttpCredentials.Bearer)?.token,
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
                val properties = Properties().apply {
                    setProperty("mail.store.protocol", "imap")
                    setProperty("mail.imap.host", connection.host)
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
                    capability = ConnectionCapability.EMAIL,
                ),
            )
            // Recursively list subfolders
            if ((subfolder.type and Folder.HOLDS_FOLDERS) != 0) {
                listFoldersRecursively(subfolder, result)
            }
        }
    }

    private suspend fun listRepositories(
        connection: ConnectionDocument,
    ): List<com.jervis.dto.connection.ConnectionResourceDto> {
        return try {
            val baseUrl = connection.baseUrl.lowercase()
            when {
                baseUrl.contains("github.com") || baseUrl.contains("github") -> {
                    listGitHubRepositories(connection)
                }
                baseUrl.contains("gitlab.com") || baseUrl.contains("gitlab") -> {
                    listGitLabRepositories(connection)
                }
                else -> {
                    logger.warn { "Unknown repository provider for connection ${connection.id}" }
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
        val credentials = connection.credentials
        val response = httpClient.get("https://api.github.com/user/repos?per_page=100") {
            when (credentials) {
                is HttpCredentials.Bearer -> {
                    header(HttpHeaders.Authorization, credentials.toAuthHeader())
                }
                else -> {}
            }
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
        val credentials = connection.credentials
        val baseUrl = connection.baseUrl.removeSuffix("/")
        val response = httpClient.get("$baseUrl/api/v4/projects?membership=true&per_page=100") {
            when (credentials) {
                is HttpCredentials.Basic -> {
                    header(HttpHeaders.Authorization, credentials.toAuthHeader())
                }
                is HttpCredentials.Bearer -> {
                    header(HttpHeaders.Authorization, credentials.toAuthHeader())
                }
                else -> {}
            }
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

    private suspend fun testHttpConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        try {
            val credentials = connectionDocument.credentials

            // Test Atlassian connectionDocument (if it's Atlassian URL)
            if (connectionDocument.baseUrl.contains("atlassian.net") == true) {
                val myself =
                    withRpcRetry(
                        name = "Atlassian",
                        reconnect = { reconnectHandler.reconnectAtlassian() },
                    ) {
                        bugTrackerClient.getUser(
                            connectionDocument.toBugTrackerUserRequest(),
                        )
                    }
                ConnectionTestResultDto(
                    success = true,
                    message = "Připojení úspěšné! Přihlášen jako: ${myself.displayName}",
                    details =
                        mapOf(
                            "id" to myself.id,
                            "email" to (myself.email ?: "N/A"),
                            "displayName" to myself.displayName,
                        ),
                )
            }
            // GitHub API test
            else if (connectionDocument.baseUrl.contains("github.com", ignoreCase = true)) {
                testGitHubConnection(connectionDocument)
            }
            // GitLab API test
            else if (connectionDocument.baseUrl.contains("gitlab.com", ignoreCase = true) ||
                connectionDocument.baseUrl.contains("gitlab", ignoreCase = true)
            ) {
                testGitLabConnection(connectionDocument)
            } else {
                // Generic HTTP test - just try to connect
                val response =
                    connectionDocument.baseUrl.let {
                        httpClient.get(it) {
                            credentials?.let { creds ->
                                when (creds) {
                                    is HttpCredentials.Basic -> {
                                        header(HttpHeaders.Authorization, creds.toAuthHeader())
                                    }

                                    is HttpCredentials.Bearer -> {
                                        header(HttpHeaders.Authorization, creds.toAuthHeader())
                                    }
                                }
                            }
                        }
                    }

                ConnectionTestResultDto(
                    success = response.status.isSuccess(),
                    message =
                        if (response.status.isSuccess()) {
                            "Připojení úspěšné! Status: ${response.status}"
                        } else {
                            "Připojení selhalo! Status: ${response.status}"
                        },
                    details =
                        mapOf(
                            "status" to response.status.toString(),
                            "url" to connectionDocument.baseUrl,
                        ) as Map<String, String>?,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "HTTP connectionDocument test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "ConnectionDocument test failed: ${e.message}",
                details =
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                    ),
            )
        }

    private suspend fun testGitHubConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        try {
            val credentials = connectionDocument.credentials
            val response =
                httpClient.get("https://api.github.com/user") {
                    when (credentials) {
                        is HttpCredentials.Bearer -> {
                            header(HttpHeaders.Authorization, credentials.toAuthHeader())
                        }

                        else -> {
                            // GitHub requires Bearer token
                        }
                    }
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                }

            if (response.status.isSuccess()) {
                val json = Json { ignoreUnknownKeys = true }
                val userResponse = json.decodeFromString(GitHubUserResponse.serializer(), response.body<String>())
                ConnectionTestResultDto(
                    success = true,
                    message = "Připojení k GitHub úspěšné! Uživatel: ${userResponse.login}",
                    details =
                        mapOf(
                            "login" to userResponse.login,
                            "id" to userResponse.id.toString(),
                            "url" to "https://api.github.com/user",
                        ),
                )
            } else {
                ConnectionTestResultDto(
                    success = false,
                    message = "GitHub připojení selhalo! Status: ${response.status}",
                    details =
                        mapOf(
                            "status" to response.status.toString(),
                            "url" to "https://api.github.com/user",
                        ),
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "GitHub connection test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "GitHub connection test failed: ${e.message}",
                details =
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                    ),
            )
        }

    private suspend fun testGitLabConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        try {
            val credentials = connectionDocument.credentials
            val baseUrl = connectionDocument.baseUrl.removeSuffix("/")
            val response =
                httpClient.get("$baseUrl/api/v4/user") {
                    when (credentials) {
                        is HttpCredentials.Basic -> {
                            header(HttpHeaders.Authorization, credentials.toAuthHeader())
                        }

                        is HttpCredentials.Bearer -> {
                            header(HttpHeaders.Authorization, credentials.toAuthHeader())
                        }

                        else -> {
                            // No authentication
                        }
                    }
                }

            if (response.status.isSuccess()) {
                val json = Json { ignoreUnknownKeys = true }
                val userResponse = json.decodeFromString(GitLabUserResponse.serializer(), response.body<String>())
                ConnectionTestResultDto(
                    success = true,
                    message = "Připojení k GitLab úspěšné! Uživatel: ${userResponse.username}",
                    details =
                        mapOf(
                            "username" to userResponse.username,
                            "id" to userResponse.id.toString(),
                            "url" to "$baseUrl/api/v4/user",
                        ),
                )
            } else {
                ConnectionTestResultDto(
                    success = false,
                    message = "GitLab připojení selhalo! Status: ${response.status}",
                    details =
                        mapOf(
                            "status" to response.status.toString(),
                            "url" to "$baseUrl/api/v4/user",
                        ),
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "GitLab connection test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "GitLab connection test failed: ${e.message}",
                details =
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
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
        when (val creds = this.credentials) {
            is HttpCredentials.Basic -> {
                BugTrackerUserRequest(
                    baseUrl = this.baseUrl,
                    authType = "BASIC",
                    basicUsername = creds.username,
                    basicPassword = creds.password,
                )
            }

            is HttpCredentials.Bearer -> {
                BugTrackerUserRequest(
                    baseUrl = this.baseUrl,
                    authType = "BEARER",
                    bearerToken = creds.token,
                )
            }

            null -> {
                BugTrackerUserRequest(
                    baseUrl = this.baseUrl,
                    authType = "NONE",
                )
            }
        }

    private suspend fun testImapConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties =
                    Properties().apply {
                        setProperty("mail.store.protocol", "imap")
                        setProperty("mail.imap.host", connectionDocument.host)
                        setProperty("mail.imap.port", connectionDocument.port.toString())
                        if (connectionDocument.useSsl) {
                            setProperty("mail.imap.ssl.enable", "true")
                            setProperty("mail.imap.ssl.trust", "*")
                        }
                        setProperty("mail.imap.connectiontimeout", "10000")
                        setProperty("mail.imap.timeout", "10000")
                    }

                val session = Session.getInstance(properties)
                val store = session.getStore("imap")

                store.connect(
                    connectionDocument.host,
                    connectionDocument.port,
                    connectionDocument.username,
                    connectionDocument.password,
                )

                val folder = store.getFolder(connectionDocument.folderName)
                folder.open(Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "IMAP connectionDocument successful! Found $messageCount messages in ${connectionDocument.folderName}",
                    details =
                        listOfNotNull(
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                            connectionDocument.username?.let { "username" to it },
                            connectionDocument.folderName.let { "folder" to it },
                            "messageCount" to messageCount.toString(),
                        ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connectionDocument failed: ${e.message}",
                    details =
                        listOfNotNull(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                        ).toMap(),
                )
            }
        }

    private suspend fun testPop3Connection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties =
                    Properties().apply {
                        setProperty("mail.store.protocol", "pop3")
                        setProperty("mail.pop3.host", connectionDocument.host)
                        setProperty("mail.pop3.port", connectionDocument.port.toString())
                        if (connectionDocument.useSsl) {
                            setProperty("mail.pop3.ssl.enable", "true")
                            setProperty("mail.pop3.ssl.trust", "*")
                        }
                        setProperty("mail.pop3.connectiontimeout", "10000")
                        setProperty("mail.pop3.timeout", "10000")
                    }

                val session = Session.getInstance(properties)
                val store = session.getStore("pop3")

                store.connect(
                    connectionDocument.host,
                    connectionDocument.port,
                    connectionDocument.username,
                    connectionDocument.password,
                )

                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "POP3 connectionDocument successful! Found $messageCount messages",
                    details =
                        listOfNotNull(
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                            connectionDocument.username?.let { "username" to it },
                            "messageCount" to messageCount.toString(),
                        ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connectionDocument failed: ${e.message}",
                    details =
                        listOfNotNull(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                        ).toMap(),
                )
            }
        }

    private suspend fun testSmtpConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            try {
                val properties =
                    Properties().apply {
                        setProperty("mail.smtp.host", connectionDocument.host)
                        setProperty("mail.smtp.port", connectionDocument.port.toString())
                        setProperty("mail.smtp.auth", "true")
                        if (connectionDocument.useTls == true) {
                            setProperty("mail.smtp.starttls.enable", "true")
                            setProperty("mail.smtp.ssl.trust", "*")
                        }
                        setProperty("mail.smtp.connectiontimeout", "10000")
                        setProperty("mail.smtp.timeout", "10000")
                    }

                val session =
                    Session.getInstance(
                        properties,
                        object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication =
                                PasswordAuthentication(
                                    connectionDocument.username,
                                    connectionDocument.password,
                                )
                        },
                    )

                val transport = session.getTransport("smtp")
                transport.connect(
                    connectionDocument.host,
                    connectionDocument.port,
                    connectionDocument.username,
                    connectionDocument.password,
                )
                transport.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "SMTP connectionDocument successful! Server is ready to send emails",
                    details =
                        listOfNotNull(
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                            connectionDocument.username?.let { "username" to it },
                            "tls" to connectionDocument.useTls.toString(),
                        ).toMap(),
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connectionDocument failed: ${e.message}",
                    details =
                        listOfNotNull(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            connectionDocument.host?.let { "host" to it },
                            "port" to connectionDocument.port.toString(),
                        ).toMap(),
                )
            }
        }
}

/**
 * Extension to convert ConnectionDocument to DTO.
 */
private fun ConnectionDocument.toDto(): ConnectionResponseDto =
    when (this.connectionType) {
        ConnectionDocument.ConnectionTypeEnum.HTTP -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = ConnectionTypeEnum.HTTP,
                provider = provider,
                name = name,
                state = state,
                baseUrl = baseUrl,
                authType =
                    when (credentials) {
                        is HttpCredentials.Basic -> HttpAuthTypeEnum.BASIC
                        is HttpCredentials.Bearer -> HttpAuthTypeEnum.BEARER
                        null -> HttpAuthTypeEnum.NONE
                    },
                httpBasicUsername = (credentials as? HttpCredentials.Basic)?.username,
                httpBasicPassword = (credentials as? HttpCredentials.Basic)?.password,
                httpBearerToken = (credentials as? HttpCredentials.Bearer)?.token,
                timeoutMs = timeoutMs,
                hasCredentials = credentials != null,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
                jiraProjectKey = jiraProjectKey,
                confluenceSpaceKey = confluenceSpaceKey,
                confluenceRootPageId = confluenceRootPageId,
                bitbucketRepoSlug = bitbucketRepoSlug,
            )
        }

        ConnectionDocument.ConnectionTypeEnum.IMAP -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = ConnectionTypeEnum.IMAP,
                provider = provider,
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useSsl = useSsl,
                folderName = folderName,
                hasCredentials = true,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
            )
        }

        ConnectionDocument.ConnectionTypeEnum.POP3 -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = ConnectionTypeEnum.POP3,
                provider = provider,
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useSsl = useSsl,
                hasCredentials = true,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
            )
        }

        ConnectionDocument.ConnectionTypeEnum.SMTP -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = ConnectionTypeEnum.SMTP,
                provider = provider,
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useSsl = useSsl,
                hasCredentials = true,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
            )
        }

        ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = ConnectionTypeEnum.OAUTH2,
                provider = provider,
                name = name,
                state = state,
                authorizationUrl = authorizationUrl,
                tokenUrl = tokenUrl,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                scope = scopes.joinToString(" "),
                hasCredentials = true,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
                jiraProjectKey = jiraProjectKey,
                confluenceSpaceKey = confluenceSpaceKey,
                confluenceRootPageId = confluenceRootPageId,
                bitbucketRepoSlug = bitbucketRepoSlug,
            )
        }

        // Note: GIT type is internal to ConnectionDocument and not exposed via DTO API
        // Git connections are handled via HTTP connections with GitHub/GitLab providers
        ConnectionDocument.ConnectionTypeEnum.GIT -> {
            error("GIT type should not be converted to DTO - use HTTP connection with GitHub/GitLab provider instead")
        }
    }

/**
 * Detect capabilities based on connection type and URL patterns.
 * This is called when creating or updating connections to auto-detect capabilities.
 */
private fun detectCapabilities(
    connectionType: ConnectionDocument.ConnectionTypeEnum,
    baseUrl: String?,
): Set<ConnectionCapability> {
    val capabilities = mutableSetOf<ConnectionCapability>()
    val url = baseUrl?.lowercase() ?: ""

    when (connectionType) {
        ConnectionDocument.ConnectionTypeEnum.HTTP -> {
            // GitHub
            if (url.contains("github.com") || url.contains("github")) {
                capabilities.add(ConnectionCapability.REPOSITORY)
                capabilities.add(ConnectionCapability.BUGTRACKER)
                capabilities.add(ConnectionCapability.WIKI)
            }
            // GitLab
            else if (url.contains("gitlab.com") || url.contains("gitlab")) {
                capabilities.add(ConnectionCapability.REPOSITORY)
                capabilities.add(ConnectionCapability.BUGTRACKER)
                capabilities.add(ConnectionCapability.WIKI)
            }
            // Atlassian (Jira/Confluence)
            else if (url.contains("atlassian.net") || url.contains("jira") || url.contains("confluence")) {
                capabilities.add(ConnectionCapability.BUGTRACKER)
                capabilities.add(ConnectionCapability.WIKI)
            }
            // Bitbucket
            else if (url.contains("bitbucket")) {
                capabilities.add(ConnectionCapability.REPOSITORY)
                capabilities.add(ConnectionCapability.BUGTRACKER)
            }
        }

        ConnectionDocument.ConnectionTypeEnum.IMAP,
        ConnectionDocument.ConnectionTypeEnum.POP3,
        ConnectionDocument.ConnectionTypeEnum.SMTP,
        -> {
            capabilities.add(ConnectionCapability.EMAIL)
        }

        ConnectionDocument.ConnectionTypeEnum.GIT -> {
            capabilities.add(ConnectionCapability.GIT)
        }

        ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
            // OAuth2 capabilities depend on the service - detected during connection test
        }
    }

    return capabilities
}

private fun mapHttpCredentials(
    authType: HttpAuthTypeEnum?,
    basicUser: String?,
    basicPass: String?,
    bearer: String?,
): HttpCredentials? {
    return when (authType) {
        null, HttpAuthTypeEnum.NONE -> {
            null
        }

        HttpAuthTypeEnum.BASIC -> {
            if (basicUser.isNullOrBlank()) return null
            HttpCredentials.Basic(basicUser, basicPass.orEmpty())
        }

        HttpAuthTypeEnum.BEARER -> {
            if (bearer.isNullOrBlank()) return null
            HttpCredentials.Bearer(bearer)
        }
    }
}

private fun inferAuthType(
    basicUser: String?,
    basicPass: String?,
    bearer: String?,
): HttpAuthTypeEnum =
    when {
        !bearer.isNullOrBlank() -> HttpAuthTypeEnum.BEARER
        !basicUser.isNullOrBlank() || !basicPass.isNullOrBlank() -> HttpAuthTypeEnum.BASIC
        else -> HttpAuthTypeEnum.NONE
    }

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
