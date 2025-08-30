package com.jervis.repository.mongo

import com.jervis.entity.mongo.ProjectDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectMongoRepository : CoroutineCrudRepository<ProjectDocument, String> {
    /**
     * Finds the project marked as active (default).
     */
    suspend fun findByIsActiveIsTrue(): ProjectDocument?

    /**
     * Lists projects scoped by client.
     */
    fun findByClientId(clientId: ObjectId): Flow<ProjectDocument>

    /**
     * Lists legacy projects without assigned clientId (for migration).
     */
    fun findByClientIdIsNull(): Flow<ProjectDocument>
}
