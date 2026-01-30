package com.jervis.repository

import com.jervis.entity.ProjectDocument
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.Flow
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
     * Find all projects that have this connection ID in their connectionIds list.
     */
    fun findByConnectionIdsContaining(connectionId: ConnectionId): Flow<ProjectDocument>
}
