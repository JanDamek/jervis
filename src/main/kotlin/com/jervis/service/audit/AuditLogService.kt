package com.jervis.service.audit

import com.jervis.entity.mongo.AuditLogDocument
import com.jervis.repository.mongo.AuditLogMongoRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AuditLogService(
    private val repo: AuditLogMongoRepository,
) {
    suspend fun start(
        inputText: String,
        userPrompt: String,
        systemPrompt: String? = null,
        clientHint: String? = null,
        projectHint: String? = null,
        contextId: ObjectId? = null,
    ): ObjectId {
        val doc =
            AuditLogDocument(
                inputText = inputText,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                responseText = null,
                errorText = null,
                clientHint = clientHint,
                projectHint = projectHint,
                contextId = contextId,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        return repo.save(doc).id
    }

    suspend fun complete(
        id: ObjectId,
        responseText: String? = null,
        errorText: String? = null,
    ): AuditLogDocument? {
        val existing = repo.findById(id.toHexString()) ?: return null
        val updated =
            existing.copy(
                responseText = responseText ?: existing.responseText,
                errorText = errorText,
                updatedAt = Instant.now(),
            )
        return repo.save(updated)
    }

    // Keep for backward compatibility where needed
    suspend fun log(
        inputText: String,
        userPrompt: String,
        responseText: String? = null,
        systemPrompt: String? = null,
        clientHint: String? = null,
        projectHint: String? = null,
        contextId: ObjectId? = null,
    ) {
        val doc =
            AuditLogDocument(
                inputText = inputText,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                responseText = responseText,
                errorText = null,
                clientHint = clientHint,
                projectHint = projectHint,
                contextId = contextId,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        repo.save(doc)
    }
}
