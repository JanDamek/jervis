package com.jervis.service.indexing.pipeline.domain

import com.jervis.domain.model.ModelType

/**
 * Pipeline item representing processed embedding ready for storage
 */
data class EmbeddingPipelineItem(
    val analysisItem: JoernAnalysisItem,
    val content: String,
    val embedding: List<Float>,
    val embeddingType: ModelType,
    val processingTimeMs: Long,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
)
