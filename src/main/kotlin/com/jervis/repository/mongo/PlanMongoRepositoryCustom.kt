package com.jervis.repository.mongo

import com.jervis.entity.mongo.PlanDocument
import org.bson.types.ObjectId

interface PlanMongoRepositoryCustom {
    suspend fun findPlansWithQuery(
        contextId: ObjectId,
        searchQuery: String,
        limit: Int,
    ): List<PlanDocument>
}
