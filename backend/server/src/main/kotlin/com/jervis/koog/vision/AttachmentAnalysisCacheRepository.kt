package com.jervis.koog.vision

import com.jervis.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for attachment analysis cache.
 *
 * Enables looking up previously analyzed attachments to avoid redundant
 * vision model processing.
 */
@Repository
interface AttachmentAnalysisCacheRepository : CoroutineCrudRepository<AttachmentAnalysisCacheDocument, ObjectId> {
    /**
     * Finds cached analysis result for a specific attachment.
     *
     * @param storagePath Path to the attachment file in storage
     * @param clientId Client that owns the attachment
     * @return Cached analysis result if found, null otherwise
     */
    suspend fun findByStoragePathAndClientId(
        storagePath: String,
        clientId: ClientId,
    ): AttachmentAnalysisCacheDocument?
}
