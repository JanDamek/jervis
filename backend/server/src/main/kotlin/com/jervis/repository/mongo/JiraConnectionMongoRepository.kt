package com.jervis.repository.mongo

import com.jervis.entity.jira.JiraConnectionDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface JiraConnectionMongoRepository : CoroutineCrudRepository<JiraConnectionDocument, ObjectId> {
    suspend fun findByClientId(clientId: ObjectId): JiraConnectionDocument?

    @Query("{ 'clientId': ?0, 'tenant': ?1 }")
    suspend fun findByClientIdAndTenant(
        clientId: ObjectId,
        tenant: String,
    ): JiraConnectionDocument?
}
