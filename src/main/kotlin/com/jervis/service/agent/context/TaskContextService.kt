package com.jervis.service.agent.context

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.repository.mongo.PlanStepMongoRepository
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.flow.collect
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
    private val planRepo: PlanMongoRepository,
    private val planStepRepo: PlanStepMongoRepository,
    private val clientService: ClientService,
    private val projectService: ProjectService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a TaskContextDocument for the given context. Resolves and enforces scope.
     */
    suspend fun create(
        clientName: String?,
        projectName: String?,
        initialQuery: String,
        originalLanguage: String,
        quick: Boolean = false,
    ): TaskContextDocument {
        val clientHint = clientName?.trim().orEmpty()
        require(clientHint.isNotBlank()) { "Client must be provided to prevent cross-context mixing" }

        // Resolve Client by name or slug (case-insensitive)
        val clients = clientService.list()
        val client =
            clients.firstOrNull {
                it.name.equals(clientHint, ignoreCase = true) ||
                    it.slug.equals(
                        clientHint.lowercase(),
                        ignoreCase = true,
                    )
            } ?: throw NoSuchElementException("Client not found: '$clientHint'")

        // Resolve Project within the chosen client (by name or slug) if provided
        val projectHint = projectName?.trim()?.takeIf { it.isNotBlank() }
        val allProjects = projectService.getAllProjects()
        val project =
            projectHint?.let { hint ->
                allProjects.firstOrNull { p ->
                    p.clientId == client.id && (
                        p.name.equals(hint, ignoreCase = true) || p.slug.equals(hint.lowercase(), ignoreCase = true)
                    )
                }
            }

        val resolvedProjectName = project?.name ?: projectHint
        if (projectHint != null && project == null) {
            logger.warn { "TASK_CONTEXT_SCOPE: Project hint '$projectHint' not found for client='${client.name}'" }
        }

        val toSave =
            TaskContextDocument(
                clientId = client.id,
                projectId = project?.id,
                clientName = client.name,
                projectName = resolvedProjectName,
                initialQuery = initialQuery,
                originalLanguage = originalLanguage,
                quick = quick,
            )
        val saved = taskContextRepo.save(toSave)
        logger.info {
            "TASK_CONTEXT_CREATED: contextId=${toSave.id} client='${client.name}' project='${resolvedProjectName ?: "(none)"}'"
        }
        return saved
    }

    /**
     * Cascading save: persists context, its active plan, and its plan steps.
     */
    suspend fun save(context: TaskContextDocument): TaskContextDocument {
        context.updatedAt = Instant.now()

        context.plans.collect { plan ->
            plan.updatedAt = Instant.now()
            plan.contextId = context.id
            planRepo.save(plan)

            plan.steps.collect { planStep ->
                planStep.apply {
                    planId = plan.id
                    contextId = plan.contextId
                }
            }

            planStepRepo.saveAll(plan.steps).collect()
        }

        return taskContextRepo.save(context)
    }
}
