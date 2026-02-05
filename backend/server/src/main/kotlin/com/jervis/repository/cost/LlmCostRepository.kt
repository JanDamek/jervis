package com.jervis.repository.cost

import com.jervis.common.types.ProjectId
import com.jervis.entity.cost.LlmCostDocument
import org.springframework.data.mongodb.repository.Aggregation
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface LlmCostRepository : CoroutineCrudRepository<LlmCostDocument, String> {
    @Aggregation(
        pipeline = [
            "{ '\$match': { 'projectId': ?0, 'timestamp': { '\$gte': ?1 } } }",
            "{ '\$group': { '_id': null, 'total': { '\$sum': '\$costUsd' } } }",
        ],
    )
    suspend fun sumCostByProjectIdSince(
        projectId: ProjectId,
        since: Instant,
    ): Double?
}
