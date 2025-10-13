package com.jervis.service

import com.jervis.entity.mongo.ScheduledTaskDocument
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Service for scheduling and managing tasks.
 * This interface is server-specific and uses server-only domain classes.
 */
interface ITaskSchedulingService {
    /**
     * Schedules a new task for execution
     */
    suspend fun scheduleTask(
        projectId: ObjectId,
        taskInstruction: String,
        taskName: String,
        scheduledAt: Instant,
        taskParameters: Map<String, String> = emptyMap(),
        priority: Int = 0,
        maxRetries: Int = 3,
        cronExpression: String? = null,
        createdBy: String = "system",
    ): ScheduledTaskDocument

    /**
     * Cancels a scheduled task
     * @return true if the task was successfully cancelled, false otherwise
     */
    suspend fun cancelTask(taskId: ObjectId): Boolean
}
