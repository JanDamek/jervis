package com.jervis.service.agent.context

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
    private val clientService: ClientService,
    private val projectService: ProjectService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a TaskContextDocument for the given contextId.
     * Resolves and enforces scope before translation: a client is mandatory; project is set when resolvable.
     */
    suspend fun create(
        contextId: ObjectId,
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
                it.name.equals(
                    clientHint,
                    ignoreCase = true,
                ) || it.slug.equals(clientHint.lowercase(), ignoreCase = true)
            }
                ?: throw NoSuchElementException("Client not found: '$clientHint'")

        // Resolve Project within the chosen client (by name or slug) if provided
        val projectHint = projectName?.trim()?.takeIf { it.isNotBlank() }
        val allProjects = projectService.getAllProjects()
        val project =
            projectHint?.let { hint ->
                allProjects.firstOrNull { p ->
                    p.clientId == client.id && (
                        p.name.equals(hint, ignoreCase = true) ||
                            p.slug.equals(
                                hint.lowercase(),
                                ignoreCase = true,
                            )
                    )
                }
            }

        val resolvedProjectName = project?.name ?: projectHint
        if (projectHint != null && project == null) {
            logger.warn { "TASK_CONTEXT_SCOPE: Project hint '$projectHint' not found for client='${client.name}'" }
        }

        val toSave =
            TaskContextDocument(
                contextId = contextId,
                clientId = client.id,
                projectId = project?.id,
                clientName = client.name, // canonical client name
                projectName = resolvedProjectName, // canonical when resolved; else original hint
                initialQuery = initialQuery,
                originalLanguage = originalLanguage,
                quick = quick,
            )
        val saved = taskContextRepo.save(toSave)
        logger.info { "TASK_CONTEXT_CREATED: contextId=$contextId client='${client.name}' project='${resolvedProjectName ?: "(none)"}'" }
        return saved
    }
}
