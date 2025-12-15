package com.jervis.service.connection

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ConnectionMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.FindAndReplaceOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

/**
 * Service for managing ConnectionDocument entities.
 * NO ENCRYPTION - credentials stored as plain text (not production app!).
 */
@Service
class ConnectionService(
    private val repository: ConnectionMongoRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Save (insert or update) connectionDocument.
     * Uses findAndReplace with upsert=true to avoid duplicate key errors.
     */
    suspend fun save(connectionDocument: ConnectionDocument): ConnectionDocument {
        val saved =
            try {
                // Use findAndReplace with upsert for atomic update/insert
                val query = Query.query(Criteria.where("_id").`is`(connectionDocument.id))
                val options = FindAndReplaceOptions.options().upsert().returnNew()
                mongoTemplate
                    .findAndReplace(query, connectionDocument, options)
                    .awaitSingle()
            } catch (e: NoSuchElementException) {
                // Fallback to insert if document doesn't exist and upsert somehow failed
                repository.save(connectionDocument)
            }

        logger.debug { "Saved connectionDocument: ${saved.name} (${saved::class.simpleName}) | id=${saved.id}" }
        return saved
    }

    /**
     * Find a connection by ID.
     */
    suspend fun findById(id: ObjectId): ConnectionDocument? = repository.findById(id)

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
    suspend fun delete(id: ObjectId) {
        repository.deleteById(id)
        logger.info { "Deleted connection: $id" }
    }
}
