package com.jervis.infrastructure.llm

import com.jervis.common.types.ProjectId
import com.jervis.infrastructure.llm.LlmCostDocument
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
