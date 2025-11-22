package com.jervis.repository

import com.jervis.entity.ScheduledTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * MongoDB repository for scheduled task operations.
 * Provides CRUD operations and custom queries for task scheduling.
 */
@Repository
interface ScheduledTaskMongoRepository : CoroutineCrudRepository<ScheduledTaskDocument, ObjectId> {
    /**
     * Find tasks by project ID
     */
    fun findByProjectId(projectId: ObjectId): Flow<ScheduledTaskDocument>

    /**
     * Find tasks by client ID
     */
    fun findByClientId(clientId: ObjectId): Flow<ScheduledTaskDocument>

    /**
     * Find tasks scheduled between two timestamps.
     * Used by scheduler loop to find tasks that should be dispatched soon (e.g., within next 10 minutes).
     */
    @Query("{ 'scheduledAt': { '\$gte': ?0, '\$lte': ?1 } }")
    fun findTasksScheduledBetween(
        from: Instant,
        to: Instant,
    ): Flow<ScheduledTaskDocument>
}
