package com.jervis.repository

import com.jervis.entity.connection.Connection
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for Connection sealed class hierarchy.
 * Spring Data MongoDB handles polymorphic queries automatically via @TypeAlias.
 *
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 */
@Repository
interface ConnectionMongoRepository : CoroutineCrudRepository<Connection, ObjectId> {

    /**
     * Find connection by unique name.
     */
    suspend fun findByName(name: String): Connection?

    /**
     * Find all enabled connections as Flow.
     */
    fun findByEnabled(enabled: Boolean): Flow<Connection>

    /**
     * Find all enabled connections (convenience for findByEnabled(true)).
     */
    fun findByEnabledTrue(): Flow<Connection>

    /**
     * Check if connection with name exists.
     */
    suspend fun existsByName(name: String): Boolean
}
