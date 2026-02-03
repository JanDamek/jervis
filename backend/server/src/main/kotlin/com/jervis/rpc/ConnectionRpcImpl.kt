package com.jervis.rpc

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.bugtracker.BugTrackerUserRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.service.IConnectionService
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ConnectionId
import io.ktor.client.HttpClient
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
                        connectionType = ConnectionDocument.ConnectionTypeEnum.HTTP,
                    )
                }

                "IMAP" -> {
                    ConnectionDocument(
                        name = request.name,
                        host = request.host ?: error("host required for IMAP connection"),
                        port = request.port ?: 993,
                        username = request.username ?: error("username required for IMAP connection"),
                        password = request.password ?: error("password required for IMAP connection"),
                        useSsl = request.useSsl ?: true,
                        connectionType = ConnectionDocument.ConnectionTypeEnum.IMAP,
                    )
                }

                "POP3" -> {
                    ConnectionDocument(
                        name = request.name,
                        host = request.host ?: error("host required for POP3 connection"),
                        port = request.port ?: 995,
                        username = request.username ?: error("username required for POP3 connection"),
                        password = request.password ?: error("password required for POP3 connection"),
                        useSsl = request.useSsl ?: true,
                        connectionType = ConnectionDocument.ConnectionTypeEnum.POP3,
                    )
                }

                "SMTP" -> {
                    ConnectionDocument(
                        name = request.name,
                        host = request.host ?: error("host required for SMTP connection"),
                        port = request.port ?: 587,
                        username = request.username ?: error("username required for SMTP connection"),
                        password = request.password ?: error("password required for SMTP connection"),
                        useTls = request.useTls ?: true,
                        connectionType = ConnectionDocument.ConnectionTypeEnum.SMTP,
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
                        scopes = listOfNotNull(request.scope),
                        redirectUri = request.redirectUri ?: error("redirectUri required for OAuth2 connection"),
                        connectionType = ConnectionDocument.ConnectionTypeEnum.OAUTH2,
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

                    existing.copy(
                        name = request.name ?: existing.name,
                        baseUrl = request.baseUrl ?: existing.baseUrl,
                        credentials = updatedCreds,
                        timeoutMs = request.timeoutMs ?: existing.timeoutMs,
                        state = request.state ?: existing.state,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.IMAP -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.POP3 -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.SMTP -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        clientSecret = request.clientSecret ?: existing.clientSecret,
                        state = request.state ?: existing.state,
                    )
                }

                ConnectionDocument.ConnectionTypeEnum.GIT -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        state = request.state ?: existing.state,
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
                    message = "ConnectionDocument successful! Logged in as: ${myself.displayName}",
                    details =
                        mapOf(
                            "id" to myself.id,
                            "email" to (myself.email ?: "N/A"),
                            "displayName" to (myself.displayName ?: "N/A"),
                        ),
                )
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
                            "ConnectionDocument successful! Status: ${response.status}"
                        } else {
                            "ConnectionDocument failed! Status: ${response.status}"
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
                        mapOf(
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                            "username" to connectionDocument.username,
                            "folder" to connectionDocument.folderName,
                            "messageCount" to messageCount.toString(),
                        ) as Map<String, String>?,
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connectionDocument failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                        ) as Map<String, String>?,
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
                        mapOf(
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                            "username" to connectionDocument.username,
                            "messageCount" to messageCount.toString(),
                        ) as Map<String, String>?,
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connectionDocument failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                        ) as Map<String, String>?,
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
                        mapOf(
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                            "username" to connectionDocument.username,
                            "tls" to connectionDocument.useTls.toString(),
                        ) as Map<String, String>?,
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connectionDocument test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connectionDocument failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connectionDocument.host,
                            "port" to connectionDocument.port.toString(),
                        ) as Map<String, String>?,
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
                hasCredentials = true,
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
                useTls = useTls,
                hasCredentials = true,
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
            )
        }

        ConnectionDocument.ConnectionTypeEnum.GIT -> {
            ConnectionResponseDto(
                id = id.toString(),
                type = "GIT",
                name = name,
                state = state,
                hasCredentials = false,
            )
        }
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
