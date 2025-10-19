package com.jervis.repository.mongo

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.entity.mongo.ScheduledTaskDocument
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
     * Find tasks by status
     */
    fun findByStatus(status: ScheduledTaskStatusEnum): Flow<ScheduledTaskDocument>

    /**
     * Find pending tasks scheduled to run before or at the given time
     */
    @Query("{ 'status': 'PENDING', 'scheduledAt': { '\$lte': ?0 } }")
    fun findPendingTasksScheduledBefore(scheduledAt: Instant): Flow<ScheduledTaskDocument>

    /**
     * Find running tasks that have been running for too long (potential stuck tasks)
     */
    @Query("{ 'status': 'RUNNING', 'startedAt': { '\$lte': ?0 } }")
    fun findStuckTasks(stuckThreshold: Instant): Flow<ScheduledTaskDocument>

    /**
     * Count tasks by status
     */
    suspend fun countByStatus(status: ScheduledTaskStatusEnum): Long
}
