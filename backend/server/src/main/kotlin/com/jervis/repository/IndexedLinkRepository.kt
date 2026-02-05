package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.entity.IndexedLinkDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IndexedLinkRepository : CoroutineCrudRepository<IndexedLinkDocument, ObjectId> {
    suspend fun findByUrl(url: String): IndexedLinkDocument?

    suspend fun findByUrlAndClientId(
        url: String,
        clientId: ClientId,
    ): IndexedLinkDocument?
}
