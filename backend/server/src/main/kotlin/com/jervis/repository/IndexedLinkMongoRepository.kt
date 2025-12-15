package com.jervis.repository

import com.jervis.entity.IndexedLinkDocument
import com.jervis.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IndexedLinkMongoRepository : CoroutineCrudRepository<IndexedLinkDocument, ObjectId> {
    suspend fun findByUrl(url: String): IndexedLinkDocument?

    suspend fun findByUrlAndClientId(
        url: String,
        clientId: ClientId,
    ): IndexedLinkDocument?
}
