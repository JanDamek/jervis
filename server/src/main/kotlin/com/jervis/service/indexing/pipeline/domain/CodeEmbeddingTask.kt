package com.jervis.service.indexing.pipeline.domain

/**
 * Pipeline task for code embedding processing
 */
data class CodeEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String,
)
