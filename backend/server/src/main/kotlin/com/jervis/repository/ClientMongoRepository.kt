package com.jervis.repository

import com.jervis.entity.ClientDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for Client documents.
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 */
@Repository
interface ClientMongoRepository : CoroutineCrudRepository<ClientDocument, ObjectId> {
    /**
     * Find all clients that use the specified connection as Flow.
     */
    fun findByConnectionIdsContaining(connectionId: ObjectId): Flow<ClientDocument>

    /**
     * Find all clients that have the deprecated atlassianConnectionId field set.
     * Used for migration purposes.
     */
    fun findByAtlassianConnectionIdIsNotNull(): Flow<ClientDocument>
}
