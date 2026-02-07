package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.entity.EnvironmentDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for environment documents.
 */
@Repository
interface EnvironmentRepository : CoroutineCrudRepository<EnvironmentDocument, EnvironmentId> {
    /**
     * Find all environments for a given client.
     */
    fun findByClientId(clientId: ClientId): Flow<EnvironmentDocument>

    /**
     * Find environment scoped to a specific project.
     */
    suspend fun findByProjectId(projectId: ProjectId): EnvironmentDocument?

    /**
     * Find environment scoped to a specific group.
     */
    suspend fun findByGroupId(groupId: ProjectGroupId): EnvironmentDocument?

    /**
     * Find client-level environment (no group and no project).
     */
    suspend fun findByClientIdAndGroupIdIsNullAndProjectIdIsNull(clientId: ClientId): EnvironmentDocument?
}
