package com.jervis.service.indexing.pipeline.domain

/**
 * Result of a pipeline indexing operation
 */
data class IndexingPipelineResult(
    val totalProcessed: Int,
    val totalErrors: Int,
    val processingTimeMs: Long,
    val throughput: Double, // items/second
    val errorMessage: String? = null,
)
