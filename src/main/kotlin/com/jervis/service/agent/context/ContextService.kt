package com.jervis.service.agent.context

import com.jervis.entity.mongo.ContextDocument
import com.jervis.repository.mongo.ContextMongoRepository
import com.jervis.service.agent.ScopeResolutionService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ContextService(
    private val contextRepo: ContextMongoRepository,
    private val scopeResolver: ScopeResolutionService,
) {
    suspend fun persistContext(
        clientName: String?,
        projectName: String?,
        autoScope: Boolean,
        englishText: String?,
        contextId: ObjectId? = null,
        sourceLanguage: String? = null,
    ): ContextDocument {
        val resolved = scopeResolver.resolve(clientName, projectName)

        val existing = contextId?.let { contextRepo.findById(it.toString()) }
        val toSave =
            if (existing != null) {
                existing.copy(
                    clientId = resolved.clientId,
                    projectId = resolved.projectId,
                    clientName = resolved.clientName,
                    projectName = resolved.projectName,
                    autoScope = autoScope,
                    englishText = englishText ?: existing.englishText,
                    sourceLanguage = sourceLanguage ?: existing.sourceLanguage,
                )
            } else {
                ContextDocument(
                    clientId = resolved.clientId,
                    projectId = resolved.projectId,
                    clientName = resolved.clientName,
                    projectName = resolved.projectName,
                    autoScope = autoScope,
                    englishText = englishText,
                    sourceLanguage = sourceLanguage,
                )
            }
        return contextRepo.save(toSave)
    }
}
