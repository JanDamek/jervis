package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Represents a context compression boundary in chat history.
 * Indicates that messages before this boundary were compressed into a summary.
 */
@Serializable
data class CompressionBoundaryDto(
    val afterSequence: Long,
    val summary: String,
    val compressedMessageCount: Int,
    val topics: List<String> = emptyList(),
)
