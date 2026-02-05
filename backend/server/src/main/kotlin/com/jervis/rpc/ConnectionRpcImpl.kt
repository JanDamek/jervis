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
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.RateLimitConfigDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.service.IConnectionService
import com.jervis.service.connection.ConnectionService
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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
) : IConnectionService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getAllConnections(): List<ConnectionResponseDto> = connectionService.findAll().map { it.toDto() }.toList()

    override suspend fun getConnectionById(id: String): ConnectionResponseDto? =
        connectionService.findById(ConnectionId.fromString(id))?.toDto()

    override suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto {
        val connectionDocument: ConnectionDocument =
            when (request.type.uppercase()) {
                "HTTP" -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.HTTP
                    ConnectionDocument(
                        name = request.name,
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

                "IMAP" -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.IMAP
                    ConnectionDocument(
                        name = request.name,
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

                "POP3" -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.POP3
                    ConnectionDocument(
                        name = request.name,
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

                "SMTP" -> {
                    val type = ConnectionDocument.ConnectionTypeEnum.SMTP
                    ConnectionDocument(
                        name = request.name,
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

                "OAUTH2" -> {
                    ConnectionDocument(
                        name = request.name,
                        authorizationUrl =
                            request.authorizationUrl
                                ?: error("authorizationUrl required for OAuth2 connection"),
                        tokenUrl = request.tokenUrl ?: error("tokenUrl required for OAuth2 connection"),
                        clientSecret = request.clientSecret ?: error("clientSecret required for OAuth2 connection"),
                        scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                        redirectUri = request.redirectUri ?: error("redirectUri required for OAuth2 connection"),
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
                    )
                }

                "GIT" -> {
                    ConnectionDocument(
                        name = request.name,
                        gitRemoteUrl = request.gitRemoteUrl ?: error("gitRemoteUrl required for GIT connection"),
                        gitProvider =
                            request.gitProvider?.let {
                                com.jervis.domain.git.GitProviderEnum
                                    .valueOf(it.uppercase())
                            },
                        connectionType = ConnectionDocument.ConnectionTypeEnum.GIT,
                        availableCapabilities = setOf(ConnectionCapability.GIT),
                    )
                }

                else -> {
                    error("Unknown connection type: ${request.type}")
                }
            }

        val created = connectionService.save(connectionDocument)
        logger.info { "Created connection: ${created.name} (${created.id})" }
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
                        baseUrl = newBaseUrl,
                        credentials = updatedCreds,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
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
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useSsl = request.useSsl ?: existing.useSsl,
                        folderName = request.folderName ?: existing.folderName,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.POP3 -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useSsl = request.useSsl ?: existing.useSsl,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.SMTP -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        useTls = request.useTls ?: existing.useTls,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        authorizationUrl = request.authorizationUrl ?: existing.authorizationUrl,
                        tokenUrl = request.tokenUrl ?: existing.tokenUrl,
                        clientSecret = request.clientSecret ?: existing.clientSecret,
                        redirectUri = request.redirectUri ?: existing.redirectUri,
                        scopes = request.scope?.split(" ")?.filter { it.isNotBlank() } ?: existing.scopes,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
                        rateLimitConfig = request.rateLimitConfig?.toEntity() ?: existing.rateLimitConfig,
                        jiraProjectKey = request.jiraProjectKey ?: existing.jiraProjectKey,
                        confluenceSpaceKey = request.confluenceSpaceKey ?: existing.confluenceSpaceKey,
                        confluenceRootPageId = request.confluenceRootPageId ?: existing.confluenceRootPageId,
                        bitbucketRepoSlug = request.bitbucketRepoSlug ?: existing.bitbucketRepoSlug,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.GIT -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        state = request.state ?: existing.state,
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
        return saved.toDto()
    }

    override suspend fun deleteConnection(id: String) {
        connectionService.delete(ConnectionId.fromString(id))
        logger.info { "Deleted connection: $id" }
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
                type = "HTTP",
                name = name,
                state = state,
                baseUrl = baseUrl,
                authType =
                    when (credentials) {
                        is HttpCredentials.Basic -> "BASIC"
                        is HttpCredentials.Bearer -> "BEARER"
                        null -> "NONE"
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
                type = "IMAP",
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
                type = "POP3",
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
                type = "SMTP",
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
                type = "OAUTH2",
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

        ConnectionDocument.ConnectionTypeEnum.GIT -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = "GIT",
                name = name,
                state = state,
                hasCredentials = false,
                capabilities = availableCapabilities,
                rateLimitConfig = rateLimitConfig.toDto(),
            )
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
    authType: String?,
    basicUser: String?,
    basicPass: String?,
    bearer: String?,
): HttpCredentials? {
    return when (authType?.uppercase()) {
        null, "NONE" -> {
            null
        }

        "BASIC" -> {
            if (basicUser.isNullOrBlank()) return null
            HttpCredentials.Basic(basicUser, basicPass.orEmpty())
        }

        "BEARER" -> {
            if (bearer.isNullOrBlank()) return null
            HttpCredentials.Bearer(bearer)
        }

        else -> {
            null
        }
    }
}

private fun inferAuthType(
    basicUser: String?,
    basicPass: String?,
    bearer: String?,
): String =
    when {
        !bearer.isNullOrBlank() -> "BEARER"
        !basicUser.isNullOrBlank() || !basicPass.isNullOrBlank() -> "BASIC"
        else -> "NONE"
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
