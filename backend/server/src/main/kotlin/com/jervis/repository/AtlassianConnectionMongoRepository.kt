package com.jervis.repository

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AtlassianConnectionMongoRepository : CoroutineCrudRepository<AtlassianConnectionDocument, ObjectId> {
    suspend fun findByClientId(clientId: ObjectId): AtlassianConnectionDocument?

    @Query("{ 'clientId': ?0, 'tenant': ?1 }")
    suspend fun findByClientIdAndTenant(
        clientId: ObjectId,
        tenant: String,
    ): AtlassianConnectionDocument?
}
