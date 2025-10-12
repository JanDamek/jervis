package com.jervis.service.scheduling

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for querying scheduled tasks without dependencies on execution services.
 * This service provides read-only access to task data and is used by tools that
 * need to browse/view tasks without causing circular dependencies.
 */
@Service
class TaskQueryService(
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
) {
    /**
     * Get tasks for a project
     */
    fun getTasksForProject(projectId: ObjectId): Flow<ScheduledTaskDocument> = scheduledTaskRepository.findByProjectId(projectId)

    /**
     * Get tasks by status
     */
    fun getTasksByStatus(status: ScheduledTaskDocument.ScheduledTaskStatus): Flow<ScheduledTaskDocument> =
        scheduledTaskRepository.findByStatus(status)
}
