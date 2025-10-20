package com.jervis.service.indexing.pipeline.domain

/**
 * Pipeline item representing storage operation result
 */
data class StoragePipelineItem(
    val analysisItem: JoernAnalysisItem,
    val success: Boolean,
    val error: String? = null,
    val workerId: Int,
    val processingTimeMs: Long = 0,
)
