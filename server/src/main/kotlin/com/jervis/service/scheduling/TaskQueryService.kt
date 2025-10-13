package com.jervis.service.scheduling

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import com.jervis.service.ITaskQueryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
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
) : ITaskQueryService {
    /**
     * Get tasks for a project (Flow version for internal use)
     */
    fun getTasksForProjectFlow(projectId: ObjectId): Flow<ScheduledTaskDocument> = scheduledTaskRepository.findByProjectId(projectId)

    /**
     * Get tasks for a project (List version for interface)
     */
    override suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByProjectId(projectId).toList()

    /**
     * Get tasks by status (Flow version for internal use)
     */
    fun getTasksByStatusFlow(status: ScheduledTaskStatus): Flow<ScheduledTaskDocument> = scheduledTaskRepository.findByStatus(status)

    /**
     * Get tasks by status (List version for interface)
     */
    override suspend fun getTasksByStatus(status: ScheduledTaskStatus): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByStatus(status).toList()
}
