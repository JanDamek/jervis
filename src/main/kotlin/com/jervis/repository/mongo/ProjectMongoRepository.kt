package com.jervis.repository.mongo

import com.jervis.entity.mongo.ProjectDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectMongoRepository : CoroutineCrudRepository<ProjectDocument, ObjectId> {
    /**
     * Finds the project marked as active (default).
     */
    suspend fun findByIsActiveIsTrue(): ProjectDocument?

    /**
     * Finds all projects belonging to a specific client.
     */
    suspend fun findByClientId(clientId: ObjectId): List<ProjectDocument>

    suspend fun findByName(name: String): ProjectDocument?
}
