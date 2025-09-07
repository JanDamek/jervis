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
     * Find existing TaskContext by ID.
     */
    suspend fun findById(contextId: ObjectId): TaskContext? {
        val contextDoc = taskContextRepo.findById(contextId) ?: return null

        val plansList =
            planMongoRepository
                .findByContextId(contextDoc.id)
                .map { planDoc ->
                    val stepsList = planStepMongoRepository.findByPlanId(planDoc.id).toList()
                    planDoc.toDomain(stepsList)
                }.toList()

        // Load full documents for context
        val clientDoc = contextDoc.clientId?.let { clientService.getClientById(it) }
        val projectDoc =
            contextDoc.projectId?.let { projectService.getAllProjects().find { p -> p.id == contextDoc.projectId } }

        return contextDoc.toDomain(plansList).copy(
            clientDocument = clientDoc!!,
            projectDocument = projectDoc!!,
        )
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

        return contextDocs.mapNotNull { contextDoc ->
            try {
                val plansList =
                    planMongoRepository
                        .findByContextId(contextDoc.id)
                        .map { planDoc ->
                            val stepsList = planStepMongoRepository.findByPlanId(planDoc.id).toList()
                            planDoc.toDomain(stepsList)
                        }.toList()

                // Load full documents for context
                val clientDoc = contextDoc.clientId?.let { clientService.getClientById(it) }
                val projectDoc =
                    contextDoc.projectId?.let {
                        projectService.getAllProjects().find { p -> p.id == contextDoc.projectId }
                    }

                if (clientDoc == null) {
                    logger.warn { "Client document not found for clientId=${contextDoc.clientId}, skipping context ${contextDoc.id}" }
                    return@mapNotNull null
                }

                if (projectDoc == null) {
                    logger.warn { "Project document not found for projectId=${contextDoc.projectId}, skipping context ${contextDoc.id}" }
                    return@mapNotNull null
                }

                contextDoc.toDomain(plansList).copy(
                    clientDocument = clientDoc,
                    projectDocument = projectDoc,
                )
            } catch (e: Exception) {
                logger.error(e) { "Error processing context document ${contextDoc.id}" }
                null
            }
        }
    }

    /**
     * Update context name.
     */
    suspend fun updateName(
        contextId: ObjectId,
        newName: String,
    ) {
        val contextDoc = taskContextRepo.findById(contextId) ?: return
        contextDoc.name = newName
        contextDoc.updatedAt = Instant.now()
        taskContextRepo.save(contextDoc)
        logger.info { "TASK_CONTEXT_NAME_UPDATED: contextId=$contextId newName='$newName'" }
    }

    /**
     * Delete context with cascading deletion of all associated plans and plan steps.
     */
    suspend fun delete(contextId: ObjectId) {
        logger.info { "TASK_CONTEXT_DELETE_START: contextId=$contextId" }

        // Find and delete all plan steps for this context
        val plans = planMongoRepository.findByContextId(contextId).toList()
        plans.forEach { plan ->
            val steps = planStepMongoRepository.findByPlanId(plan.id).toList()
            steps.forEach { step ->
                planStepMongoRepository.delete(step)
                logger.debug { "TASK_CONTEXT_DELETE: Deleted step ${step.id} from plan ${plan.id}" }
            }
        }

        // Delete all plans for this context
        plans.forEach { plan ->
            planMongoRepository.delete(plan)
            logger.debug { "TASK_CONTEXT_DELETE: Deleted plan ${plan.id}" }
        }

        // Finally delete the context itself
        taskContextRepo.deleteById(contextId)
        logger.info { "TASK_CONTEXT_DELETE_COMPLETE: contextId=$contextId deleted with ${plans.size} plans" }
    }
}
