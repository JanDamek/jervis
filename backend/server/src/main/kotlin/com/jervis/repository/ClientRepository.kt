package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.entity.ClientDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for Client documents.
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 * NOTE: Do NOT use the inherited findById() — it causes AopInvocationException at runtime
 * with Kotlin inline value class IDs. Use getById() defined here instead.
 */
@Repository
interface ClientRepository : CoroutineCrudRepository<ClientDocument, ClientId> {
    /**
     * Find client by ID. Use this instead of the inherited findById(ClientId) to avoid
     * AOP proxy issues with Kotlin inline value classes.
     * Spring Data derives the query from the method name — no @Query needed.
     */
    suspend fun getById(id: ClientId): ClientDocument?

    /**
     * Find all clients that use the specified connection as Flow.
     */
    fun findByConnectionIdsContaining(connectionId: ConnectionId): Flow<ClientDocument>
}
