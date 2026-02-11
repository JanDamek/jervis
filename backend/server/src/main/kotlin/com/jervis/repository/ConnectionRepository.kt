package com.jervis.repository

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for ConnectionDocument-sealed class hierarchy.
 * Spring Data MongoDB handles polymorphic queries automatically via @TypeAlias.
 *
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 */
@Repository
interface ConnectionRepository : CoroutineCrudRepository<ConnectionDocument, ConnectionId> {
    /**
     * Find connection by ID. Use this instead of the inherited findById(ConnectionId) to avoid
     * AOP proxy issues with Kotlin inline value classes.
     */
    suspend fun getById(id: ConnectionId): ConnectionDocument?

    /**
     * Find all connections by state.
     * Uses an explicit query because 'state' is defined in subclasses, not on sealed class.
     */
    fun findAllByState(state: ConnectionStateEnum): Flow<ConnectionDocument>
}
