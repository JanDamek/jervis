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
     * Find session by client and project.
     */
    suspend fun findByClientIdAndProjectId(
        clientId: ClientId,
        projectId: ProjectId,
    ): ChatSessionDocument?

    /**
     * Delete sessions inactive for more than specified duration (for cleanup).
     */
    suspend fun deleteByLastActivityAtBefore(cutoffTime: Instant): Long
}
