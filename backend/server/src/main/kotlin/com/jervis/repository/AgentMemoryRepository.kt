package com.jervis.repository

import com.jervis.domain.agent.AgentMemoryDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface AgentMemoryRepository : CoroutineCrudRepository<AgentMemoryDocument, ObjectId> {

    /** Find all memories for a client, sorted by timestamp DESC */
    fun findByClientIdOrderByTimestampDesc(
        clientId: String,
    ): Flow<AgentMemoryDocument>

    /** Find memories for a project */
    fun findByClientIdAndProjectIdOrderByTimestampDesc(
        clientId: String,
        projectId: String,
    ): Flow<AgentMemoryDocument>

    /** Find memories by correlation ID (celá session/workflow) */
    fun findByClientIdAndCorrelationIdOrderByTimestamp(
        clientId: String,
        correlationId: String,
    ): Flow<AgentMemoryDocument>

    /** Find memories by action type */
    fun findByClientIdAndActionTypeOrderByTimestampDesc(
        clientId: String,
        actionType: String,
    ): Flow<AgentMemoryDocument>

    /** Find memories by entity */
    fun findByClientIdAndEntityTypeAndEntityKeyOrderByTimestampDesc(
        clientId: String,
        entityType: String,
        entityKey: String,
    ): Flow<AgentMemoryDocument>

    /** Find memories by tags */
    fun findByClientIdAndTagsInOrderByTimestampDesc(
        clientId: String,
        tags: List<String>,
    ): Flow<AgentMemoryDocument>

    /** Find memories in time range */
    fun findByClientIdAndTimestampBetweenOrderByTimestampDesc(
        clientId: String,
        from: Instant,
        to: Instant,
    ): Flow<AgentMemoryDocument>
}
