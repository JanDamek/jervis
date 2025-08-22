package com.jervis.service.agent.context

import com.jervis.entity.mongo.ContextDocument
import com.jervis.repository.mongo.ContextMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ContextService(
    private val contextRepo: ContextMongoRepository,
    private val clientService: ClientService,
    private val projectService: ProjectService,
) {
    suspend fun persistContext(
        clientName: String?,
        projectName: String?,
        autoScope: Boolean,
        englishText: String?,
        contextId: ObjectId? = null,
        sourceLanguage: String? = null,
    ): ContextDocument {
        val clients = clientService.list()
        val projects = projectService.getAllProjects()
        val client = clients.firstOrNull { it.name == clientName }
        val project = projects.firstOrNull { it.name == projectName }

        val existing = contextId?.let { contextRepo.findById(it.toString()) }
        val toSave =
            if (existing != null) {
                existing.copy(
                    clientId = client?.id,
                    projectId = project?.id,
                    clientName = clientName,
                    projectName = projectName,
                    autoScope = autoScope,
                    englishText = englishText ?: existing.englishText,
                    sourceLanguage = sourceLanguage ?: existing.sourceLanguage,
                )
            } else {
                ContextDocument(
                    clientId = client?.id,
                    projectId = project?.id,
                    clientName = clientName,
                    projectName = projectName,
                    autoScope = autoScope,
                    englishText = englishText,
                    sourceLanguage = sourceLanguage,
                )
            }
        return contextRepo.save(toSave)
    }
}
