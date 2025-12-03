package com.jervis.controller.api

import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.connection.RateLimitConfig
import com.jervis.service.IConnectionService
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.connection.ConnectionService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for managing connections.
 *
 * Provides CRUD operations and testing functionality for all connection types.
 * UI uses this to configure connections from first application start.
 */
@RestController
@RequestMapping("/api/connections")
class ConnectionRestController(
    private val connectionService: ConnectionService,
    private val httpClient: HttpClient,
    private val atlassianApiClient: AtlassianApiClient,
) : IConnectionService {
    private val logger = KotlinLogging.logger {}

    @GetMapping
    override suspend fun getAllConnections(): List<ConnectionResponseDto> = connectionService.findAll().map { it.toDto() }.toList()

    override suspend fun getConnectionById(id: String): ConnectionResponseDto? = connectionService.findById(ObjectId(id))?.toDto()

    @GetMapping("/{id}")
    suspend fun getConnection(
        @PathVariable id: String,
    ): ConnectionResponseDto {
        val connection =
            connectionService.findById(ObjectId(id))
                ?: throw IllegalArgumentException("Connection not found: $id")
        return connection.toDto()
    }

    @PostMapping
    override suspend fun createConnection(
        @RequestBody request: ConnectionCreateRequestDto,
    ): ConnectionResponseDto {
        val connection: Connection =
            when (request.type.uppercase()) {
                "HTTP" -> {
                    Connection.HttpConnection(
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
                        rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
                    )
                }

                "IMAP" -> {
                    Connection.ImapConnection(
                        name = request.name,
                        host = request.host ?: error("host required for IMAP connection"),
                        port = request.port ?: 993,
                        username = request.username ?: error("username required for IMAP connection"),
                        password = request.password ?: error("password required for IMAP connection"),
                        useSsl = request.useSsl ?: true,
                        rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
                    )
                }

                "POP3" -> {
                    Connection.Pop3Connection(
                        name = request.name,
                        host = request.host ?: error("host required for POP3 connection"),
                        port = request.port ?: 995,
                        username = request.username ?: error("username required for POP3 connection"),
                        password = request.password ?: error("password required for POP3 connection"),
                        useSsl = request.useSsl ?: true,
                        rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
                    )
                }

                "SMTP" -> {
                    Connection.SmtpConnection(
                        name = request.name,
                        host = request.host ?: error("host required for SMTP connection"),
                        port = request.port ?: 587,
                        username = request.username ?: error("username required for SMTP connection"),
                        password = request.password ?: error("password required for SMTP connection"),
                        useTls = request.useTls ?: true,
                        rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
                    )
                }

                "OAUTH2" -> {
                    Connection.OAuth2Connection(
                        name = request.name,
                        authorizationUrl =
                            request.authorizationUrl
                                ?: error("authorizationUrl required for OAuth2 connection"),
                        tokenUrl = request.tokenUrl ?: error("tokenUrl required for OAuth2 connection"),
                        clientId = request.clientId ?: error("clientId required for OAuth2 connection"),
                        clientSecret = request.clientSecret ?: error("clientSecret required for OAuth2 connection"),
                        scopes = listOfNotNull(request.scope),
                        redirectUri = request.redirectUri ?: error("redirectUri required for OAuth2 connection"),
                        rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
                    )
                }

                else -> {
                    error("Unknown connection type: ${request.type}")
                }
            }
        connection.state = request.state

        val created = connectionService.save(connection)
        logger.info { "Created connection: ${created.name} (${created.id})" }
        return created.toDto()
    }

    @PutMapping("/{id}")
    override suspend fun updateConnection(
        @PathVariable id: String,
        @RequestBody request: ConnectionUpdateRequestDto,
    ): ConnectionResponseDto {
        val existing =
            connectionService.findById(ObjectId(id))
                ?: throw IllegalArgumentException("Connection not found: $id")

        val updated: Connection =
            when (existing) {
                is Connection.HttpConnection -> {
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
                    )
                }

                is Connection.ImapConnection -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                    )
                }

                is Connection.Pop3Connection -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                    )
                }

                is Connection.SmtpConnection -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                    )
                }

                is Connection.OAuth2Connection -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        clientSecret = request.clientSecret ?: existing.clientSecret,
                    )
                }
            }
        updated.state = request.state ?: existing.state

        val saved = connectionService.save(updated)
        logger.info { "Updated connection: ${saved.name}" }
        return saved.toDto()
    }

    @DeleteMapping("/{id}")
    override suspend fun deleteConnection(
        @PathVariable id: String,
    ) {
        connectionService.delete(ObjectId(id))
        logger.info { "Deleted connection: $id" }
    }

    @PostMapping("/{id}/test")
    override suspend fun testConnection(
        @PathVariable id: String,
    ): ConnectionTestResultDto {
        val connection =
            connectionService.findById(ObjectId(id))
                ?: throw IllegalArgumentException("Connection not found: $id")

        return try {
            // Test connection based on type
            when (connection) {
                is Connection.HttpConnection -> {
                    testHttpConnection(connection)
                }

                is Connection.ImapConnection -> {
                    testImapConnection(connection)
                }

                is Connection.Pop3Connection -> {
                    testPop3Connection(connection)
                }

                is Connection.SmtpConnection -> {
                    testSmtpConnection(connection)
                }

                is Connection.OAuth2Connection -> {
                    ConnectionTestResultDto(
                        success = true,
                        message = "OAuth2 test not yet implemented",
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
            )
        }
    }

    private suspend fun testHttpConnection(connection: Connection.HttpConnection): ConnectionTestResultDto =
        try {
            val credentials = connection.credentials

            // Test Atlassian connection (if it's Atlassian URL)
            if (connection.baseUrl.contains("atlassian.net")) {
                val myself = atlassianApiClient.getMyself(connection, credentials)
                ConnectionTestResultDto(
                    success = true,
                    message = "Connection successful! Logged in as: ${myself.displayName}",
                    details =
                        mapOf(
                            "accountId" to myself.accountId,
                            "email" to (myself.emailAddress ?: "N/A"),
                            "displayName" to myself.displayName,
                        ),
                )
            } else {
                // Generic HTTP test - just try to connect
                val response =
                    httpClient.get(connection.baseUrl) {
                        credentials?.let { creds ->
                            when (creds) {
                                is HttpCredentials.Basic -> {
                                    header(HttpHeaders.Authorization, creds.toAuthHeader())
                                }

                                is HttpCredentials.Bearer -> {
                                    header(
                                        HttpHeaders.Authorization,
                                        creds.toAuthHeader(),
                                    )
                                }
                            }
                        }
                    }

                ConnectionTestResultDto(
                    success = response.status.isSuccess(),
                    message =
                        if (response.status.isSuccess()) {
                            "Connection successful! Status: ${response.status}"
                        } else {
                            "Connection failed! Status: ${response.status}"
                        },
                    details =
                        mapOf(
                            "status" to response.status.toString(),
                            "url" to connection.baseUrl,
                        ),
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "HTTP connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
                details =
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                    ),
            )
        }

    private suspend fun testImapConnection(connection: Connection.ImapConnection): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
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

                val session = jakarta.mail.Session.getInstance(properties)
                val store = session.getStore("imap")

                store.connect(connection.host, connection.port, connection.username, connection.password)

                val folder = store.getFolder(connection.folderName)
                folder.open(jakarta.mail.Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "IMAP connection successful! Found $messageCount messages in ${connection.folderName}",
                    details =
                        mapOf(
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                            "username" to connection.username,
                            "folder" to connection.folderName,
                            "messageCount" to messageCount.toString(),
                        ),
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connection failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                        ),
                )
            }
        }

    private suspend fun testPop3Connection(connection: Connection.Pop3Connection): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
                        setProperty("mail.store.protocol", "pop3")
                        setProperty("mail.pop3.host", connection.host)
                        setProperty("mail.pop3.port", connection.port.toString())
                        if (connection.useSsl) {
                            setProperty("mail.pop3.ssl.enable", "true")
                            setProperty("mail.pop3.ssl.trust", "*")
                        }
                        setProperty("mail.pop3.connectiontimeout", "10000")
                        setProperty("mail.pop3.timeout", "10000")
                    }

                val session = jakarta.mail.Session.getInstance(properties)
                val store = session.getStore("pop3")

                store.connect(connection.host, connection.port, connection.username, connection.password)

                val folder = store.getFolder("INBOX")
                folder.open(jakarta.mail.Folder.READ_ONLY)
                val messageCount = folder.messageCount

                folder.close(false)
                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "POP3 connection successful! Found $messageCount messages",
                    details =
                        mapOf(
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                            "username" to connection.username,
                            "messageCount" to messageCount.toString(),
                        ),
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connection failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                        ),
                )
            }
        }

    private suspend fun testSmtpConnection(connection: Connection.SmtpConnection): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
                        setProperty("mail.smtp.host", connection.host)
                        setProperty("mail.smtp.port", connection.port.toString())
                        setProperty("mail.smtp.auth", "true")
                        if (connection.useTls) {
                            setProperty("mail.smtp.starttls.enable", "true")
                            setProperty("mail.smtp.ssl.trust", "*")
                        }
                        setProperty("mail.smtp.connectiontimeout", "10000")
                        setProperty("mail.smtp.timeout", "10000")
                    }

                val session =
                    jakarta.mail.Session.getInstance(
                        properties,
                        object : jakarta.mail.Authenticator() {
                            override fun getPasswordAuthentication(): jakarta.mail.PasswordAuthentication =
                                jakarta.mail.PasswordAuthentication(connection.username, connection.password)
                        },
                    )

                val transport = session.getTransport("smtp")
                transport.connect(connection.host, connection.port, connection.username, connection.password)
                transport.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "SMTP connection successful! Server is ready to send emails",
                    details =
                        mapOf(
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                            "username" to connection.username,
                            "tls" to connection.useTls.toString(),
                        ),
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connection failed: ${e.message}",
                    details =
                        mapOf(
                            "error" to (e.message ?: "Unknown error"),
                            "errorType" to e::class.simpleName!!,
                            "host" to connection.host,
                            "port" to connection.port.toString(),
                        ),
                )
            }
        }
}

/**
 * Extension to convert Connection to DTO.
 */
private fun Connection.toDto(): ConnectionResponseDto =
    when (this) {
        is Connection.HttpConnection -> {
            ConnectionResponseDto(
                id = id.toHexString(),
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
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is Connection.ImapConnection -> {
            ConnectionResponseDto(
                id = id.toHexString(),
                type = "IMAP",
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useSsl = useSsl,
                hasCredentials = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is Connection.Pop3Connection -> {
            ConnectionResponseDto(
                id = id.toHexString(),
                type = "POP3",
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useSsl = useSsl,
                hasCredentials = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is Connection.SmtpConnection -> {
            ConnectionResponseDto(
                id = id.toHexString(),
                type = "SMTP",
                name = name,
                state = state,
                host = host,
                port = port,
                username = username,
                password = password,
                useTls = useTls,
                hasCredentials = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is Connection.OAuth2Connection -> {
            ConnectionResponseDto(
                id = id.toHexString(),
                type = "OAUTH2",
                name = name,
                state = state,
                authorizationUrl = authorizationUrl,
                tokenUrl = tokenUrl,
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                scope = scopes.joinToString(" "),
                hasCredentials = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
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
