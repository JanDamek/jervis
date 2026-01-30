package com.jervis.entity.cost

import com.jervis.types.ProjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Persists LLM cost for each request to track monthly budget.
 */
@Document(collection = "llm_costs")
data class LlmCostDocument(
    @Id
    val id: String? = null,
    val projectId: ProjectId,
    val modelId: String,
    val provider: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val timestamp: Instant = Instant.now()
)
