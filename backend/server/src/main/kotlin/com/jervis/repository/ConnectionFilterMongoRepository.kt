package com.jervis.repository

import com.jervis.entity.connection.ConnectionFilterDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ConnectionFilterMongoRepository : CoroutineCrudRepository<ConnectionFilterDocument, ObjectId> {
    @Query("{ 'connectionId': ?0, 'tool': ?1 }")
    suspend fun findByConnectionIdAndTool(
        connectionId: ObjectId,
        tool: String,
    ): ConnectionFilterDocument?
}
