package com.jervis.repository

import com.jervis.entity.ClientProjectLinkDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientProjectLinkMongoRepository : CoroutineCrudRepository<ClientProjectLinkDocument, String> {
    fun findByClientId(clientId: ObjectId): Flow<ClientProjectLinkDocument>

    suspend fun findByClientIdAndProjectId(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument?

    suspend fun deleteByClientIdAndProjectId(
        clientId: ObjectId,
        projectId: ObjectId,
    )
}
