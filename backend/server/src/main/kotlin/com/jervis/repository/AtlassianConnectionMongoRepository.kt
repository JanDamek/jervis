package com.jervis.repository

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AtlassianConnectionMongoRepository : CoroutineCrudRepository<AtlassianConnectionDocument, ObjectId> {
    @Query("{ 'tenant': ?0 }")
    suspend fun findByTenant(tenant: String): AtlassianConnectionDocument?

    @Query("{ 'tenant': ?0, 'email': ?1 }")
    suspend fun findByTenantAndEmail(
        tenant: String,
        email: String?,
    ): AtlassianConnectionDocument?
}
