package com.jervis.repository.mongo

import com.jervis.entity.mongo.ContextThreadLinkDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ContextThreadLinkMongoRepository : CoroutineCrudRepository<ContextThreadLinkDocument, ObjectId> {
    suspend fun findByThreadKey(threadKey: String): ContextThreadLinkDocument?
}
