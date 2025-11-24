package com.jervis.controller.api

import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.entity.connection.Connection
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.connection.ConnectionService
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

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
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping
    suspend fun getAllConnections(): List<ConnectionResponseDto> {
        return connectionService.findAll().map { it.toDto() }.toList()
    }

    @GetMapping("/{id}")
    suspend fun getConnection(@PathVariable id: String): ConnectionResponseDto {
        val connection = connectionService.findById(ObjectId(id))
            ?: throw IllegalArgumentException("Connection not found: $id")
        return connection.toDto()
    }

    @PostMapping
    suspend fun createConnection(
        @RequestBody request: ConnectionCreateRequestDto
    ): ConnectionResponseDto {
        val connection = request.toEntity()
        val created = connectionService.create(connection)
        logger.info { "Created connection: ${created.name} (${created.id})" }
        return created.toDto()
    }

    @PutMapping("/{id}")
    suspend fun updateConnection(
        @PathVariable id: String,
        @RequestBody request: ConnectionUpdateRequestDto
    ): ConnectionResponseDto {
        val existing = connectionService.findById(ObjectId(id))
            ?: throw IllegalArgumentException("Connection not found: $id")

        val updated = request.applyTo(existing)
        val saved = connectionService.update(updated)
        logger.info { "Updated connection: ${saved.name}" }
        return saved.toDto()
    }

    @DeleteMapping("/{id}")
    suspend fun deleteConnection(@PathVariable id: String) {
        connectionService.delete(ObjectId(id))
        logger.info { "Deleted connection: $id" }
    }

    @PostMapping("/{id}/test")
    suspend fun testConnection(@PathVariable id: String): ConnectionTestResultDto {
        val connection = connectionService.findById(ObjectId(id))
            ?: throw IllegalArgumentException("Connection not found: $id")

        return try {
            // Test connection based on type
            when (connection) {
                is Connection.HttpConnection -> testHttpConnection(connection)
                is Connection.ImapConnection -> testImapConnection(connection)
                is Connection.Pop3Connection -> testPop3Connection(connection)
                is Connection.SmtpConnection -> testSmtpConnection(connection)
                is Connection.OAuth2Connection -> ConnectionTestResultDto(success = true, message = "OAuth2 test not yet implemented")
            }
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}"
            )
        }
    }

    private suspend fun testHttpConnection(connection: Connection.HttpConnection): ConnectionTestResultDto {
        return try {
            // Parse credentials (plain text, no decryption needed)
            val credentials = connectionService.parseCredentials(connection)

            // Test Atlassian connection (if it's Atlassian URL)
            if (connection.baseUrl.contains("atlassian.net")) {
                val myself = atlassianApiClient.getMyself(connection, credentials)
                ConnectionTestResultDto(
                    success = true,
                    message = "Connection successful! Logged in as: ${myself.displayName}",
                    details = mapOf(
                        "accountId" to myself.accountId,
                        "email" to (myself.emailAddress ?: "N/A"),
                        "displayName" to myself.displayName
                    )
                )
            } else {
                // Generic HTTP test - just try to connect
                val response = httpClient.get(connection.baseUrl) {
                    credentials?.let { creds ->
                        when (creds) {
                            is com.jervis.entity.connection.HttpCredentials.Basic -> {
                                header(HttpHeaders.Authorization, creds.toAuthHeader())
                            }
                            is com.jervis.entity.connection.HttpCredentials.Bearer -> {
                                header(HttpHeaders.Authorization, creds.toAuthHeader())
                            }
                            is com.jervis.entity.connection.HttpCredentials.ApiKey -> {
                                header(creds.headerName, creds.apiKey)
                            }
                        }
                    }
                }

                ConnectionTestResultDto(
                    success = response.status.isSuccess(),
                    message = if (response.status.isSuccess()) {
                        "Connection successful! Status: ${response.status}"
                    } else {
                        "Connection failed! Status: ${response.status}"
                    },
                    details = mapOf(
                        "status" to response.status.toString(),
                        "url" to connection.baseUrl
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "HTTP connection test failed for ${connection.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
                details = mapOf(
                    "error" to (e.message ?: "Unknown error"),
                    "errorType" to e::class.simpleName!!
                )
            )
        }
    }

    private suspend fun testImapConnection(connection: Connection.ImapConnection): ConnectionTestResultDto {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties = java.util.Properties().apply {
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
                    details = mapOf(
                        "host" to connection.host,
                        "port" to connection.port.toString(),
                        "username" to connection.username,
                        "folder" to connection.folderName,
                        "messageCount" to messageCount.toString()
                    )
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connection failed: ${e.message}",
                    details = mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        "host" to connection.host,
                        "port" to connection.port.toString()
                    )
                )
            }
        }
    }

    private suspend fun testPop3Connection(connection: Connection.Pop3Connection): ConnectionTestResultDto {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties = java.util.Properties().apply {
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
                    details = mapOf(
                        "host" to connection.host,
                        "port" to connection.port.toString(),
                        "username" to connection.username,
                        "messageCount" to messageCount.toString()
                    )
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connection failed: ${e.message}",
                    details = mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        "host" to connection.host,
                        "port" to connection.port.toString()
                    )
                )
            }
        }
    }

    private suspend fun testSmtpConnection(connection: Connection.SmtpConnection): ConnectionTestResultDto {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val properties = java.util.Properties().apply {
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

                val session = jakarta.mail.Session.getInstance(properties, object : jakarta.mail.Authenticator() {
                    override fun getPasswordAuthentication(): jakarta.mail.PasswordAuthentication {
                        return jakarta.mail.PasswordAuthentication(connection.username, connection.password)
                    }
                })

                val transport = session.getTransport("smtp")
                transport.connect(connection.host, connection.port, connection.username, connection.password)
                transport.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "SMTP connection successful! Server is ready to send emails",
                    details = mapOf(
                        "host" to connection.host,
                        "port" to connection.port.toString(),
                        "username" to connection.username,
                        "tls" to connection.useTls.toString()
                    )
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connection test failed for ${connection.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connection failed: ${e.message}",
                    details = mapOf(
                        "error" to (e.message ?: "Unknown error"),
                        "errorType" to e::class.simpleName!!,
                        "host" to connection.host,
                        "port" to connection.port.toString()
                    )
                )
            }
        }
    }
}

/**
 * Extension to convert Connection to DTO.
 */
private fun Connection.toDto(): ConnectionResponseDto {
    return when (this) {
        is Connection.HttpConnection -> ConnectionResponseDto(
            id = id.toString(),
            type = "HTTP",
            name = name,
            enabled = enabled,
            baseUrl = baseUrl,
            authType = authType.name,
            hasCredentials = credentials != null,
            rateLimitConfig = rateLimitConfig,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli()
        )
        is Connection.ImapConnection -> ConnectionResponseDto(
            id = id.toString(),
            type = "IMAP",
            name = name,
            enabled = enabled,
            host = host,
            port = port,
            username = username,
            hasCredentials = true,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli()
        )
        is Connection.Pop3Connection -> ConnectionResponseDto(
            id = id.toString(),
            type = "POP3",
            name = name,
            enabled = enabled,
            host = host,
            port = port,
            username = username,
            hasCredentials = true,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli()
        )
        is Connection.SmtpConnection -> ConnectionResponseDto(
            id = id.toString(),
            type = "SMTP",
            name = name,
            enabled = enabled,
            host = host,
            port = port,
            username = username,
            hasCredentials = true,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli()
        )
        is Connection.OAuth2Connection -> ConnectionResponseDto(
            id = id.toString(),
            type = "OAUTH2",
            name = name,
            enabled = enabled,
            authorizationUrl = authorizationUrl,
            tokenUrl = tokenUrl,
            clientId = clientId,
            hasCredentials = true,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli()
        )
    }
}
