package com.jervis.repository

import com.jervis.entity.connection.Connection
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
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
     * Find all connections by state.
     * Uses explicit query because 'state' is defined in subclasses, not on sealed class.
     */
    @Query("{ 'state': ?0 }")
    fun findByState(state: String): Flow<Connection>
}
