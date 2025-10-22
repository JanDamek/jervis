package com.jervis.dto.monitoring

import kotlinx.serialization.Serializable

/**
 * Represents progress information for an indexing step
 */
@Serializable
data class IndexingProgressDto(
    val current: Int,
    val total: Int,
    val percentage: Double = if (total > 0) (current.toDouble() / total * 100) else 0.0,
    val estimatedTimeRemaining: Long? = null,
)
