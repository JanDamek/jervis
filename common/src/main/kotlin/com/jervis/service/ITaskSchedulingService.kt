package com.jervis.service

import com.jervis.entity.mongo.ScheduledTaskDocument
import org.bson.types.ObjectId
import java.time.Instant

interface ITaskSchedulingService {
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

    suspend fun cancelTask(taskId: ObjectId): Boolean
}
