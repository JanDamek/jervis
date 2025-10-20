package com.jervis.service.indexing.pipeline.domain

/**
 * Pipeline task for text embedding processing
 */
data class TextEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String,
)
