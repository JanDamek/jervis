package com.jervis.service.connection

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.Properties

/**
 * Service for managing ConnectionDocument entities.
 * NO ENCRYPTION - credentials stored as plain text (not production app!).
 */
@Service
class ConnectionService(
    private val repository: ConnectionRepository,
    private val httpClient: HttpClient,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Save (insert or update) connectionDocument.
     * Uses findAndReplace with upsert=true to avoid duplicate key errors.
     */
    suspend fun save(connectionDocument: ConnectionDocument): ConnectionDocument = repository.save(connectionDocument)

    /**
     * Find a connection by ID.
     *
     * NOTE: Spring Data MongoDB has issues with @JvmInline value classes as ID.
     * Workaround: Load all and filter in-memory (acceptable since connections are few).
     */
    suspend fun findById(id: ConnectionId): ConnectionDocument? {
        val connections = mutableListOf<ConnectionDocument>()
        repository.findAll().collect { connections.add(it) }
        return connections.find { it.id == id }
    }

    /**
     * Find all VALID connections as Flow.
     * Only VALID connections are eligible for polling and indexing.
     */
    fun findAllValid(): Flow<ConnectionDocument> = repository.findAllByState(ConnectionStateEnum.VALID)

    /**
     * Find all connections as Flow.
     */
    fun findAll(): Flow<ConnectionDocument> = repository.findAll()

    /**
     * Delete connection by ID.
     */
    suspend fun delete(id: ConnectionId) {
        // Spring Data MongoDB has issues with @JvmInline value classes as ID
        // Workaround: find first, then delete
        findById(id)?.let { repository.delete(it) }
        logger.info { "Deleted connection: $id" }
    }

    /**
     * Find VALID connection for a given domain (e.g., "tepsivo.atlassian.net").
     * Used to detect if a link points to a known service the client has connected.
     *
     * @param domain The domain to search for (e.g., "tepsivo.atlassian.net", "jira.company.com")
     * @return ConnectionDocument if found and VALID, null otherwise
     */
    suspend fun findValidConnectionByDomain(domain: String): ConnectionDocument? {
        val connections = mutableListOf<ConnectionDocument>()
        findAllValid().collect { connections.add(it) }

        // Match exact domain or baseUrl contains domain
        return connections.find { connection ->
            connection.baseUrl.contains(domain, ignoreCase = true) ||
                connection.host?.equals(domain, ignoreCase = true) == true
        }
    }

    /**
     * Test connection based on its protocol.
     *
     * @param connectionDocument connection to test
     * @return ConnectionTestResultDto with success status and message
     */
    suspend fun testConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        try {
            when (connectionDocument.protocol) {
                ProtocolEnum.HTTP -> testHttpConnection(connectionDocument)
                ProtocolEnum.IMAP -> testImapConnection(connectionDocument)
                ProtocolEnum.POP3 -> testPop3Connection(connectionDocument)
                ProtocolEnum.SMTP -> testSmtpConnection(connectionDocument)
            }
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
            )
        }

    private suspend fun testHttpConnection(connectionDocument: ConnectionDocument): ConnectionTestResultDto =
        try {
            val response =
                httpClient.get(connectionDocument.baseUrl) {
                    connectionDocument.toAuthHeader()?.let { authHeader ->
                        header(HttpHeaders.Authorization, authHeader)
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
            )
        } catch (e: Exception) {
            logger.error(e) { "HTTP connection test failed for ${connectionDocument.name}" }
            ConnectionTestResultDto(
                success = false,
                message = "Connection test failed: ${e.message}",
            )
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

                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "IMAP connection successful!",
                )
            } catch (e: Exception) {
                logger.error(e) { "IMAP connection test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "IMAP connection failed: ${e.message}",
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

                store.close()

                ConnectionTestResultDto(
                    success = true,
                    message = "POP3 connection successful!",
                )
            } catch (e: Exception) {
                logger.error(e) { "POP3 connection test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "POP3 connection failed: ${e.message}",
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

                val session = Session.getInstance(properties)
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
                    message = "SMTP connection successful! Server is ready to send emails",
                )
            } catch (e: Exception) {
                logger.error(e) { "SMTP connection test failed for ${connectionDocument.name}" }
                ConnectionTestResultDto(
                    success = false,
                    message = "SMTP connection failed: ${e.message}",
                )
            }
        }
}
