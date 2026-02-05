package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.AgentPreferenceDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for agent preferences.
 */
@Repository
interface AgentPreferenceRepository : CoroutineCrudRepository<AgentPreferenceDocument, ObjectId> {
    /**
     * Find preference by exact scope and key.
     */
    suspend fun findByClientIdAndProjectIdAndKey(
        clientId: ClientId?,
        projectId: ProjectId?,
        key: String,
    ): AgentPreferenceDocument?

    /**
     * Find all preferences for a specific scope (global/client/project).
     */
    fun findByClientIdAndProjectId(
        clientId: ClientId?,
        projectId: ProjectId?,
    ): Flow<AgentPreferenceDocument>

    /**
     * Find all global preferences.
     */
    fun findByClientIdIsNullAndProjectIdIsNull(): Flow<AgentPreferenceDocument>
}
