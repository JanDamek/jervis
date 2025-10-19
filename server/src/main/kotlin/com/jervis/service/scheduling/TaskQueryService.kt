package com.jervis.service.scheduling

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.repository.mongo.PlanStepMongoRepository
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import com.jervis.repository.mongo.TaskContextMongoRepository
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
    private val taskContextRepository: TaskContextMongoRepository,
    private val planRepository: PlanMongoRepository,
    private val planStepRepository: PlanStepMongoRepository,
) {
    suspend fun findContextById(contextId: ObjectId): TaskContext? {
        val doc = taskContextRepository.findById(contextId) ?: return null
        val plans = planRepository.findByContextId(contextId).toList().map { it.toDomain() }
        return doc.toDomain(plans)
    }

    suspend fun findPlanById(planId: ObjectId): Plan? {
        val doc = planRepository.findById(planId.toHexString()) ?: return null
        val steps = planStepRepository.findByPlanId(planId).toList()
        return doc.toDomain(steps)
    }

    suspend fun listContextsForClient(clientId: ObjectId): List<TaskContext> =
        taskContextRepository
            .findByClientId(clientId)
            .toList()
            .map {
                val plans = planRepository.findByContextId(it.id).toList().map { p -> p.toDomain() }
                it.toDomain(plans)
            }

    suspend fun listPlansForContext(contextId: ObjectId): List<Plan> =
        planRepository
            .findByContextId(contextId)
            .toList()
            .map {
                val steps = planStepRepository.findByPlanId(it.id).toList()
                it.toDomain(steps)
            }

    suspend fun listActivePlans(): List<Plan> {
        // Pro nyní vrátíme prázdný seznam - jako nejasnou věc necháme na příští iteraci
        // TODO: Implementovat findAllByStatus v PlanMongoRepository
        return emptyList()
    }

    suspend fun searchContexts(
        clientId: ObjectId?,
        projectId: ObjectId?,
        query: String?,
    ): List<TaskContext> {
        val docs =
            when {
                clientId != null && projectId != null ->
                    taskContextRepository
                        .findByClientIdAndProjectId(
                            clientId,
                            projectId,
                        ).toList()

                clientId != null -> taskContextRepository.findByClientId(clientId).toList()
                else -> emptyList()
            }
        return docs.map {
            val plans = planRepository.findByContextId(it.id).toList().map { p -> p.toDomain() }
            it.toDomain(plans)
        }
    }

    fun getTasksForProjectFlow(projectId: ObjectId): Flow<ScheduledTaskDocument> = scheduledTaskRepository.findByProjectId(projectId)

    suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByProjectId(projectId).toList()

    fun getTasksByStatusFlow(status: ScheduledTaskStatusEnum): Flow<ScheduledTaskDocument> = scheduledTaskRepository.findByStatus(status)

    suspend fun getTasksByStatus(status: ScheduledTaskStatusEnum): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByStatus(status).toList()
}
