package com.jervis.service.triage

import com.jervis.entity.mongo.ContextThreadLinkDocument
import com.jervis.repository.mongo.ContextThreadLinkMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ContextThreadLinkService(
    private val repository: ContextThreadLinkMongoRepository,
) {
    suspend fun findContextId(threadKey: String?): ObjectId? =
        withContext(Dispatchers.IO) {
            threadKey?.let { repository.findByThreadKey(it)?.contextId }
        }

    suspend fun link(
        threadKey: String?,
        contextId: ObjectId,
    ) {
        if (threadKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            val existing = repository.findByThreadKey(threadKey)
            if (existing == null) {
                repository.save(ContextThreadLinkDocument(threadKey = threadKey, contextId = contextId))
            }
        }
    }
}
