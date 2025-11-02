package com.jervis.repository.mongo

import com.jervis.entity.ConfluenceAccountDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ConfluenceAccountMongoRepository : CoroutineCrudRepository<ConfluenceAccountDocument, ObjectId> {
    /**
     * Find next account to poll (oldest lastPolledAt first).
     * Used by polling scheduler to cycle through accounts.
     */
    suspend fun findFirstByIsActiveTrueOrderByLastPolledAtAsc(): ConfluenceAccountDocument?

    /**
     * Find all active accounts for a client.
     */
    @Query("{ 'clientId': ?0, 'isActive': true }")
    suspend fun findActiveByClientId(clientId: ObjectId): List<ConfluenceAccountDocument>

    /**
     * Find all active accounts for a project.
     */
    @Query("{ 'projectId': ?0, 'isActive': true }")
    suspend fun findActiveByProjectId(projectId: ObjectId): List<ConfluenceAccountDocument>
}
