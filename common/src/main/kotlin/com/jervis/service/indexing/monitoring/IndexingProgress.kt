package com.jervis.service.indexing.monitoring

/**
 * Represents progress information for an indexing step
 */
data class IndexingProgress(
    val current: Int,
    val total: Int,
    val percentage: Double = if (total > 0) (current.toDouble() / total * 100) else 0.0,
    val estimatedTimeRemaining: Long? = null, // milliseconds
)
