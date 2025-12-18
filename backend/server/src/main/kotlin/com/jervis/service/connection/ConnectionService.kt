package com.jervis.service.connection

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ConnectionMongoRepository
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for managing ConnectionDocument entities.
 * NO ENCRYPTION - credentials stored as plain text (not production app!).
 */
@Service
class ConnectionService(
    private val repository: ConnectionMongoRepository,
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
}
