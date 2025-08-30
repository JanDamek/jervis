package com.jervis.repository.mongo

import com.jervis.entity.mongo.PlanDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PlanMongoRepository : CoroutineCrudRepository<PlanDocument, String> {
    suspend fun findByContextId(contextId: ObjectId): PlanDocument?
}
