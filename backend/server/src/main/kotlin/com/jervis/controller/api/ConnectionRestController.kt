package com.jervis.controller.api

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.AtlassianAuth
import com.jervis.common.dto.atlassian.AtlassianConnection
import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.connection.RateLimitConfig
import com.jervis.service.IConnectionService
import com.jervis.service.connection.ConnectionService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
    private val atlassianClient: IAtlassianClient,
) : IConnectionService {
    private val logger = KotlinLogging.logger {}

    @GetMapping
    override suspend fun getAllConnections(): List<ConnectionResponseDto> = connectionService.findAll().map { it.toDto() }.toList()

    override suspend fun getConnectionById(id: String): ConnectionResponseDto? = connectionService.findById(ObjectId(id))?.toDto()

    @GetMapping("/{id}")
    suspend fun getConnection(
        @PathVariable id: String,
    ): ConnectionResponseDto {
        val connectionDocument =
            connectionService.findById(ObjectId(id))
                ?: throw IllegalArgumentException("ConnectionDocument not found: $id")
        return connectionDocument.toDto()
    }

    @PostMapping
    override suspend fun createConnection(
        @RequestBody request: ConnectionCreateRequestDto,
    ): ConnectionResponseDto {
        val connectionDocument: ConnectionDocument =
            when (request.type.uppercase()) {
                "HTTP" -> {
                    ConnectionDocument.HttpConnectionDocument(
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
                    ConnectionDocument.ImapConnectionDocument(
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
                    ConnectionDocument.Pop3ConnectionDocument(
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
                    ConnectionDocument.SmtpConnectionDocument(
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
                    ConnectionDocument.OAuth2ConnectionDocument(
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

        val created = connectionService.save(connectionDocument)
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
                ?: throw IllegalArgumentException("ConnectionDocument not found: $id")

        val updated: ConnectionDocument =
            when (existing) {
                is ConnectionDocument.HttpConnectionDocument -> {
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

                is ConnectionDocument.ImapConnectionDocument -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                is ConnectionDocument.Pop3ConnectionDocument -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                is ConnectionDocument.SmtpConnectionDocument -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        host = request.host ?: existing.host,
                        port = request.port ?: existing.port,
                        username = request.username ?: existing.username,
                        password = request.password ?: existing.password,
                        state = request.state ?: existing.state,
                    )
                }

                is ConnectionDocument.OAuth2ConnectionDocument -> {
                    existing.copy(
                        name = request.name ?: existing.name,
                        clientSecret = request.clientSecret ?: existing.clientSecret,
                        state = request.state ?: existing.state,
                    )
                }
            }

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
        val connectionDocument =
            connectionService.findById(ObjectId(id))
                ?: throw IllegalArgumentException("ConnectionDocument not found: $id")

        return try {
            // Test connection based on type
            when (connectionDocument) {
                is ConnectionDocument.HttpConnectionDocument -> {
                    testHttpConnection(connectionDocument)
                }

                is ConnectionDocument.ImapConnectionDocument -> {
                    testImapConnection(connectionDocument)
                }

                is ConnectionDocument.Pop3ConnectionDocument -> {
                    testPop3Connection(connectionDocument)
                }

                is ConnectionDocument.SmtpConnectionDocument -> {
                    testSmtpConnection(connectionDocument)
                }

                is ConnectionDocument.OAuth2ConnectionDocument -> {
                    ConnectionTestResultDto(
                        success = true,
                        message = "OAuth2 test not yet implemented",
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

    private suspend fun testHttpConnection(connectionDocument: ConnectionDocument.HttpConnectionDocument): ConnectionTestResultDto =
        try {
            val credentials = connectionDocument.credentials

            // Test Atlassian connectionDocument (if it's Atlassian URL)
            if (connectionDocument.baseUrl.contains("atlassian.net")) {
                val atlConn = connectionDocument.toAtlassianConnection()
                val myself =
                    atlassianClient.getMyself(
                        atlConn.toHeaderValue(),
                        connectionDocument.toAtlassianMyselfRequest(),
                    )
                ConnectionTestResultDto(
                    success = true,
                    message = "ConnectionDocument successful! Logged in as: ${myself.displayName}",
                    details =
                        mapOf(
                            "accountId" to (myself.accountId ?: "N/A"),
                            "email" to (myself.emailAddress ?: "N/A"),
                            "displayName" to (myself.displayName ?: "N/A"),
                        ),
                )
            } else {
                // Generic HTTP test - just try to connect
                val response =
                    httpClient.get(connectionDocument.baseUrl) {
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
                        ),
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

    private fun ConnectionDocument.HttpConnectionDocument.toAtlassianConnection(): AtlassianConnection =
        AtlassianConnection(
            baseUrl = this.baseUrl,
            auth =
                when (val creds = this.credentials) {
                    is HttpCredentials.Basic -> AtlassianAuth.Basic(creds.username, creds.password)
                    is HttpCredentials.Bearer -> AtlassianAuth.Bearer(creds.token)
                    null -> AtlassianAuth.None
                },
            timeoutMs = this.timeoutMs,
        )

    private fun AtlassianConnection.toHeaderValue(): String {
        val json =
            buildJsonObject {
                put("baseUrl", baseUrl)
                put("timeoutMs", timeoutMs)
                putJsonObject("auth") {
                    when (val a = auth) {
                        is AtlassianAuth.None -> {
                            put("type", "NONE")
                        }

                        is AtlassianAuth.Basic -> {
                            put("type", "BASIC")
                            put("username", a.username)
                            put("password", a.password)
                        }

                        is AtlassianAuth.Bearer -> {
                            put("type", "BEARER")
                            put("token", a.token)
                        }
                    }
                }
            }
        val str = Json.encodeToString(JsonObject.serializer(), json)
        return java.util.Base64
            .getEncoder()
            .encodeToString(str.toByteArray())
    }

    private fun ConnectionDocument.HttpConnectionDocument.toAtlassianMyselfRequest(): AtlassianMyselfRequest =
        when (val creds = this.credentials) {
            is HttpCredentials.Basic -> {
                AtlassianMyselfRequest(
                    baseUrl = this.baseUrl,
                    authType = "BASIC",
                    basicUsername = creds.username,
                    basicPassword = creds.password,
                )
            }

            is HttpCredentials.Bearer -> {
                AtlassianMyselfRequest(
                    baseUrl = this.baseUrl,
                    authType = "BEARER",
                    bearerToken = creds.token,
                )
            }

            null -> {
                AtlassianMyselfRequest(
                    baseUrl = this.baseUrl,
                    authType = "NONE",
                )
            }
        }

    private suspend fun testImapConnection(connectionDocument: ConnectionDocument.ImapConnectionDocument): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
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

                val session = jakarta.mail.Session.getInstance(properties)
                val store = session.getStore("imap")

                store.connect(
                    connectionDocument.host,
                    connectionDocument.port,
                    connectionDocument.username,
                    connectionDocument.password,
                )

                val folder = store.getFolder(connectionDocument.folderName)
                folder.open(jakarta.mail.Folder.READ_ONLY)
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
                        ),
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
                        ),
                )
            }
        }

    private suspend fun testPop3Connection(connectionDocument: ConnectionDocument.Pop3ConnectionDocument): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
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

                val session = jakarta.mail.Session.getInstance(properties)
                val store = session.getStore("pop3")

                store.connect(
                    connectionDocument.host,
                    connectionDocument.port,
                    connectionDocument.username,
                    connectionDocument.password,
                )

                val folder = store.getFolder("INBOX")
                folder.open(jakarta.mail.Folder.READ_ONLY)
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
                        ),
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
                        ),
                )
            }
        }

    private suspend fun testSmtpConnection(connectionDocument: ConnectionDocument.SmtpConnectionDocument): ConnectionTestResultDto =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties =
                    java.util.Properties().apply {
                        setProperty("mail.smtp.host", connectionDocument.host)
                        setProperty("mail.smtp.port", connectionDocument.port.toString())
                        setProperty("mail.smtp.auth", "true")
                        if (connectionDocument.useTls) {
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
                                jakarta.mail.PasswordAuthentication(
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
                        ),
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
                        ),
                )
            }
        }
}

/**
 * Extension to convert ConnectionDocument to DTO.
 */
private fun ConnectionDocument.toDto(): ConnectionResponseDto =
    when (this) {
        is ConnectionDocument.HttpConnectionDocument -> {
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
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is ConnectionDocument.ImapConnectionDocument -> {
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
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is ConnectionDocument.Pop3ConnectionDocument -> {
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
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is ConnectionDocument.SmtpConnectionDocument -> {
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
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

        is ConnectionDocument.OAuth2ConnectionDocument -> {
            ConnectionResponseDto(
                id = id.toString(),
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
