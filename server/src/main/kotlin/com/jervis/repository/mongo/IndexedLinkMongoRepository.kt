package com.jervis.repository.mongo

import com.jervis.entity.IndexedLinkDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IndexedLinkMongoRepository : CoroutineCrudRepository<IndexedLinkDocument, org.bson.types.ObjectId> {
    suspend fun findByUrl(url: String): IndexedLinkDocument?
}
