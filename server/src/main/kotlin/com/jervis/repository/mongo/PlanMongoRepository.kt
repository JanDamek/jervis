package com.jervis.repository.mongo

import com.jervis.entity.mongo.PlanDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PlanMongoRepository :
    CoroutineCrudRepository<PlanDocument, String>,
    PlanMongoRepositoryCustom {
    fun findByContextId(contextId: ObjectId): Flow<PlanDocument>
}
