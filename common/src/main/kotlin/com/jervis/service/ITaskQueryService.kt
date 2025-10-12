package com.jervis.service

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.entity.mongo.ScheduledTaskDocument
import org.bson.types.ObjectId

interface ITaskQueryService {
    suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument>

    suspend fun getTasksByStatus(status: ScheduledTaskStatus): List<ScheduledTaskDocument>
}
