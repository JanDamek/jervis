package com.jervis.service.connection

import com.jervis.entity.connection.AuthType
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.repository.ConnectionMongoRepository
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing Connection entities.
 * NO ENCRYPTION - credentials stored as plain text (not production app!).
 */
@Service
class ConnectionService(
    private val repository: ConnectionMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create new connection (plain text credentials).
     */
    suspend fun create(connection: Connection): Connection {
        val saved = repository.save(connection)
        logger.info { "Created connection: ${saved.name} (${saved::class.simpleName})" }
        return saved
    }

    /**
     * Update existing connection.
     */
    suspend fun update(connection: Connection): Connection {
        val updated = connection.withUpdatedAt(Instant.now())
        val saved = repository.save(updated)
        logger.info { "Updated connection: ${saved.name}" }
        return saved
    }

    /**
     * Find connection by ID.
     */
    suspend fun findById(id: ObjectId): Connection? {
        return repository.findById(id)
    }

    /**
     * Find connection by name.
     */
    suspend fun findByName(name: String): Connection? {
        return repository.findByName(name)
    }

    /**
     * Find all enabled connections as Flow.
     */
    fun findAllEnabled(): Flow<Connection> {
        return repository.findByEnabledTrue()
    }

    /**
     * Find all connections as Flow.
     */
    fun findAll(): Flow<Connection> {
        return repository.findAll()
    }

    /**
     * Delete connection by ID.
     */
    suspend fun delete(id: ObjectId) {
        repository.deleteById(id)
        logger.info { "Deleted connection: $id" }
    }

    /**
     * Parse credentials for HTTP requests (plain text, no decryption needed).
     */
    fun parseCredentials(connection: Connection): HttpCredentials? {
        return when (connection) {
            is Connection.HttpConnection -> {
                if (connection.credentials == null) return null

                when (connection.authType) {
                    AuthType.BASIC -> {
                        val parts = connection.credentials.split(":", limit = 2)
                        if (parts.size != 2) throw IllegalStateException("Invalid BASIC credentials format: expected 'username:password'")
                        HttpCredentials.Basic(parts[0], parts[1])
                    }

                    AuthType.BEARER -> HttpCredentials.Bearer(connection.credentials)
                    AuthType.API_KEY -> {
                        val parts = connection.credentials.split(":", limit = 2)
                        if (parts.size != 2) throw IllegalStateException("Invalid API_KEY credentials format: expected 'headerName:apiKey'")
                        HttpCredentials.ApiKey(parts[0], parts[1])
                    }

                    AuthType.NONE -> null
                }
            }

            else -> null // Other connection types don't use HttpCredentials
        }
    }

    /**
     * Helper to update updatedAt timestamp.
     */
    private fun Connection.withUpdatedAt(instant: Instant): Connection {
        return when (this) {
            is Connection.HttpConnection -> copy(updatedAt = instant)
            is Connection.ImapConnection -> copy(updatedAt = instant)
            is Connection.Pop3Connection -> copy(updatedAt = instant)
            is Connection.SmtpConnection -> copy(updatedAt = instant)
            is Connection.OAuth2Connection -> copy(updatedAt = instant)
        }
    }
}
