package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.GuidelinesDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GuidelinesRepository : CoroutineCrudRepository<GuidelinesDocument, String> {
    suspend fun findByClientIdAndProjectId(clientId: ClientId?, projectId: ProjectId?): GuidelinesDocument?

    fun findByClientId(clientId: ClientId): Flow<GuidelinesDocument>

    suspend fun findByClientIdIsNullAndProjectIdIsNull(): GuidelinesDocument?
}
