package com.jervis.repository

import com.jervis.entity.ChatSessionDocument
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ChatSessionRepository : CoroutineCrudRepository<ChatSessionDocument, String> {
    /**
     * Find session by client and project (project can be null for general chat).
     */
    suspend fun findByClientIdAndProjectId(
        clientId: ClientId,
        projectId: ProjectId?,
    ): ChatSessionDocument?

    /**
     * Find session by client only (when projectId is null).
     */
    suspend fun findByClientId(clientId: ClientId): ChatSessionDocument?

    /**
     * Delete sessions inactive for more than specified duration (for cleanup).
     */
    suspend fun deleteByLastActivityAtBefore(cutoffTime: Instant): Long
}
