package com.jervis.service.connection

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.Connection
import com.jervis.repository.ConnectionMongoRepository
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

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
     * Update existing connection.
     */
    suspend fun save(connection: Connection): Connection {
        repository.findById(connection.id)?.let {
            repository.deleteById(connection.id)
            logger.debug { "Removed connection: ${connection.name} before store." }
        }
        val saved = repository.save(connection)
        logger.info { "Stored connection: ${saved.name} (${saved::class.simpleName})" }
        return saved
    }

    /**
     * Find a connection by ID.
     */
    suspend fun findById(id: ObjectId): Connection? = repository.findById(id)

    /**
     * Find all VALID connections as Flow.
     * Only VALID connections are eligible for polling and indexing.
     */
    fun findAllValid(): Flow<Connection> = repository.findAllByState(ConnectionStateEnum.VALID)

    /**
     * Find all connections as Flow.
     */
    fun findAll(): Flow<Connection> = repository.findAll()

    /**
     * Delete connection by ID.
     */
    suspend fun delete(id: ObjectId) {
        repository.deleteById(id)
        logger.info { "Deleted connection: $id" }
    }
}
