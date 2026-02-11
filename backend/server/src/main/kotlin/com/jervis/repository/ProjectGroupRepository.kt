package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.entity.ProjectGroupDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project group documents.
 */
@Repository
interface ProjectGroupRepository : CoroutineCrudRepository<ProjectGroupDocument, ProjectGroupId> {
    /**
     * Find project group by ID. Use this instead of the inherited findById(ProjectGroupId) to avoid
     * AOP proxy issues with Kotlin inline value classes.
     */
    suspend fun getById(id: ProjectGroupId): ProjectGroupDocument?

    /**
     * Find all project groups for a given client.
     */
    fun findByClientId(clientId: ClientId): Flow<ProjectGroupDocument>
}
