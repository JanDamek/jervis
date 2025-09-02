package com.jervis.service.agent.context

import com.jervis.domain.context.TaskContext
import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStepDocument
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.repository.mongo.PlanStepMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val planMongoRepository: PlanMongoRepository,
    private val planStepMongoRepository: PlanStepMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a TaskContext for the given context. Resolves and enforces scope.
     */
    suspend fun create(
        clientId: ObjectId,
        projectId: ObjectId,
        quick: Boolean = false,
    ): TaskContext {
        // Resolve Client by name or slug (case-insensitive)
        val client = clientMongoRepository.findById(clientId)

        // Resolve Project within the chosen client (by name) if provided
        val project = projectMongoRepository.findById(projectId)

        val toSave =
            TaskContextDocument(
                clientId = client!!.id,
                projectId = project!!.id,
                clientName = client.name,
                projectName = project.name,
                quick = quick,
            )
        val saved = taskContextRepo.save(toSave)
        logger.info {
            "TASK_CONTEXT_CREATED: contextId=${saved.id} client='${client.name}' project='${project.name}' quick=$quick"
        }

        val plansList =
            planMongoRepository
                .findByContextId(saved.id)
                .map { planDoc ->
                    val stepsList = planStepMongoRepository.findByPlanId(planDoc.id).toList()
                    planDoc.toDomain(stepsList)
                }.toList()

        return saved.toDomain(plansList).copy(
            clientDocument = client,
            projectDocument = project,
        )
    }

    /**
     * Cascading save: persists context, its active plan, and its plan steps.
     */
    suspend fun save(context: TaskContext) {
        val contextDoc =
            TaskContextDocument.fromDomain(context).apply {
                updatedAt = Instant.now()
            }

        context.plans.forEach { plan ->
            val planDoc =
                PlanDocument.fromDomain(plan).apply {
                    updatedAt = Instant.now()
                    contextId = contextDoc.id
                }
            planMongoRepository.save(planDoc)

            plan.steps.forEach { planStep ->
                val stepDoc =
                    PlanStepDocument.fromDomain(planStep).apply {
                        planId = planDoc.id
                        contextId = planDoc.contextId
                    }
                planStepMongoRepository.save(stepDoc)
            }
        }

        taskContextRepo.save(contextDoc)
        logger.info { "TASK_CONTEXT_SAVED: contextId=${contextDoc.id}" }
    }

    /**
     * List contexts for a given client and optional project.
     */
    suspend fun listFor(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): List<TaskContext> {
        val contextDocs =
            if (projectId == null) {
                taskContextRepo.findByClientId(clientId).toList()
            } else {
                taskContextRepo.findByClientIdAndProjectId(clientId, projectId).toList()
            }

        return contextDocs.map { contextDoc ->
            val plansList =
                planMongoRepository
                    .findByContextId(contextDoc.id)
                    .map { planDoc ->
                        val stepsList = planStepMongoRepository.findByPlanId(planDoc.id).toList()
                        planDoc.toDomain(stepsList)
                    }.toList()

            // Load full documents for context
            val clientDoc = contextDoc.clientId.let { clientService.getClientById(it!!) }
            val projectDoc =
                contextDoc.projectId.let { projectService.getAllProjects().find { p -> p.id == contextDoc.projectId } }

            contextDoc.toDomain(plansList).copy(
                clientDocument = clientDoc!!,
                projectDocument = projectDoc!!,
            )
        }
    }
}
