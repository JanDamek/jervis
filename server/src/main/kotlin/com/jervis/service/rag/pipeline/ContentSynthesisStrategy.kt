package com.jervis.service.rag.pipeline

import com.jervis.domain.context.TaskContext
import com.jervis.service.rag.RagService

/**
 * Strategy for synthesizing final answer from query results.
 */
fun interface ContentSynthesisStrategy {
    suspend fun synthesize(
        queryResults: List<RagService.QueryResult>,
        originalQuery: String,
        context: TaskContext,
    ): String
}
