package com.jervis.repository

import com.jervis.entity.ClientDocument
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for Client documents.
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 * NOTE: ID type is ObjectId, not ClientId (inline value classes don't work well with Spring Data)
 */
@Repository
interface ClientRepository : CoroutineCrudRepository<ClientDocument, ClientId> {
    /**
     * Find all clients that use the specified connection as Flow.
     */
    fun findByConnectionIdsContaining(connectionId: ConnectionId): Flow<ClientDocument>
}
