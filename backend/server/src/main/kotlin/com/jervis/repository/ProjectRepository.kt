package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.ProjectDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectRepository : CoroutineCrudRepository<ProjectDocument, ProjectId> {
    /**
     * Find all projects for a given client.
     */
    fun findByClientId(clientId: ClientId): Flow<ProjectDocument>

    /**
     * Find all projects that reference a connection in their resources list.
     */
    @Query("{ 'resources.connectionId': ?0 }")
    fun findByResourcesConnectionId(connectionId: ObjectId): Flow<ProjectDocument>
}
