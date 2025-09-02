package com.jervis.repository.mongo

import com.jervis.entity.mongo.PlanDocument
import kotlinx.coroutines.reactive.awaitSingle
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class PlanMongoRepositoryCustomImpl(
    private val mongoTemplate: ReactiveMongoTemplate,
) : PlanMongoRepositoryCustom {
    override suspend fun findPlansWithQuery(
        contextId: ObjectId,
        searchQuery: String,
        limit: Int,
    ): List<PlanDocument> {
        // For now, return plans by contextId and filter in memory
        // In production, this should use MongoDB text search
        val query = Query.query(Criteria.where("contextId").`is`(contextId))

        val plans = mongoTemplate.find(query, PlanDocument::class.java).collectList().awaitSingle()

        return plans.take(limit).filter { plan ->
            plan.originalQuestion.contains(searchQuery, ignoreCase = true) ||
                plan.englishQuestion.contains(searchQuery, ignoreCase = true) ||
                plan.contextSummary?.contains(searchQuery, ignoreCase = true) == true ||
                plan.finalAnswer?.contains(searchQuery, ignoreCase = true) == true
        }
    }
}
