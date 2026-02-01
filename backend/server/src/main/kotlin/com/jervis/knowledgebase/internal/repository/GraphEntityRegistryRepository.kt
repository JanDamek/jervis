package com.jervis.knowledgebase.internal.repository

import com.jervis.knowledgebase.internal.entity.GraphEntityRegistryDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

internal interface GraphEntityRegistryRepository : CoroutineCrudRepository<GraphEntityRegistryDocument, ObjectId> {
    suspend fun findFirstByClientIdAndAliasKey(
        clientId: String,
        aliasKey: String,
    ): GraphEntityRegistryDocument?

    suspend fun findFirstByClientIdAndCanonicalKey(
        clientId: String,
        canonicalKey: String,
    ): GraphEntityRegistryDocument?

    fun findAllByClientIdAndArea(
        clientId: String,
        area: String,
    ): Flow<GraphEntityRegistryDocument>
}
