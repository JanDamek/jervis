package com.jervis.repository.mongo

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.entity.mongo.ScheduledTaskStatus
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
     * Find tasks by project ID and status
     */
    fun findByProjectIdAndStatus(
        projectId: ObjectId,
        status: ScheduledTaskStatus,
    ): Flow<ScheduledTaskDocument>

    /**
     * Find tasks by project ID
     */
    fun findByProjectId(projectId: ObjectId): Flow<ScheduledTaskDocument>

    /**
     * Find tasks by status
     */
    fun findByStatus(status: ScheduledTaskStatus): Flow<ScheduledTaskDocument>

    /**
     * Find pending tasks scheduled to run before or at the given time
     */
    @Query("{ 'status': 'PENDING', 'scheduledAt': { '\$lte': ?0 } }")
    fun findPendingTasksScheduledBefore(scheduledAt: Instant): Flow<ScheduledTaskDocument>

    /**
     * Find tasks that need to be retried (failed tasks with retry count less than max retries)
     */
    @Query("{ 'status': 'FAILED', 'retryCount': { '\$lt': '\$maxRetries' } }")
    fun findTasksForRetry(): Flow<ScheduledTaskDocument>

    /**
     * Find running tasks that have been running for too long (potential stuck tasks)
     */
    @Query("{ 'status': 'RUNNING', 'startedAt': { '\$lte': ?0 } }")
    fun findStuckTasks(stuckThreshold: Instant): Flow<ScheduledTaskDocument>

    /**
     * Find tasks created between two timestamps
     */
    fun findByCreatedAtBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<ScheduledTaskDocument>

    /**
     * Find tasks by creator
     */
    fun findByCreatedBy(createdBy: String): Flow<ScheduledTaskDocument>

    /**
     * Count tasks by status
     */
    suspend fun countByStatus(status: ScheduledTaskStatus): Long

    /**
     * Count tasks by project ID and status
     */
    suspend fun countByProjectIdAndStatus(
        projectId: ObjectId,
        status: ScheduledTaskStatus,
    ): Long

    /**
     * Delete completed tasks older than the specified time
     */
    @Query(value = "{ 'status': 'COMPLETED', 'completedAt': { '\$lte': ?0 } }", delete = true)
    suspend fun deleteCompletedTasksOlderThan(cutoffTime: Instant): Long

    /**
     * Delete cancelled tasks older than the specified time
     */
    @Query(value = "{ 'status': 'CANCELLED', 'lastUpdatedAt': { '\$lte': ?0 } }", delete = true)
    suspend fun deleteCancelledTasksOlderThan(cutoffTime: Instant): Long
}
