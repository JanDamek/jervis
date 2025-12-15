package com.jervis.repository

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for ConnectionDocument-sealed class hierarchy.
 * Spring Data MongoDB handles polymorphic queries automatically via @TypeAlias.
 *
 * Uses CoroutineCrudRepository (NOT ReactiveMongoRepository!) for proper Kotlin Flow support.
 */
@Repository
interface ConnectionMongoRepository : CoroutineCrudRepository<ConnectionDocument, ObjectId> {
    /**
     * Find all connections by state.
     * Uses an explicit query because 'state' is defined in subclasses, not on sealed class.
     */
    @Query("{'state': ?0}")
    fun findAllByState(state: ConnectionStateEnum): Flow<ConnectionDocument>
}
