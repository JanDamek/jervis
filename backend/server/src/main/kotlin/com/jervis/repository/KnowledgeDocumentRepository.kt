package com.jervis.repository

import com.jervis.entity.knowledge.KnowledgeDocument
import com.jervis.types.ClientId
import com.jervis.types.SourceUrn
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface KnowledgeDocumentRepository : CoroutineCrudRepository<KnowledgeDocument, ObjectId> {
    suspend fun findFirstByClientIdAndSourceUrnAndContentHash(
        clientId: ClientId,
        sourceUrn: SourceUrn,
        contentHash: String,
    ): KnowledgeDocument?

    fun findAllByClientId(clientId: ClientId): Flow<KnowledgeDocument>
}
