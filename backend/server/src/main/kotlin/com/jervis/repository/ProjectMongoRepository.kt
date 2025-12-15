package com.jervis.repository

import com.jervis.entity.ProjectDocument
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectMongoRepository : CoroutineCrudRepository<ProjectDocument, ProjectId> {
    suspend fun findByName(name: String): ProjectDocument?

    /**
     * Find all projects that have this connection ID in their connectionIds list.
     */
    fun findByConnectionIdsContaining(connectionId: ObjectId): Flow<ProjectDocument>
}
