package com.jervis.service

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.entity.mongo.ScheduledTaskDocument
import org.bson.types.ObjectId

/**
 * Service for querying scheduled tasks.
 * This interface is server-specific and uses server-only domain classes.
 */
interface ITaskQueryService {
    /**
     * Gets all tasks for a specific project
     */
    suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument>

    /**
     * Gets all tasks with a specific status
     */
    suspend fun getTasksByStatus(status: ScheduledTaskStatus): List<ScheduledTaskDocument>
}
