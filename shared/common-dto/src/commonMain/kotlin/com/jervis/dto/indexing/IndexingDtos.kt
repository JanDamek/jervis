package com.jervis.dto.indexing

import kotlinx.serialization.Serializable

@Serializable
enum class IndexingStateDto { IDLE, RUNNING }

@Serializable
data class IndexingOverviewDto(
    val tools: List<IndexingToolSummaryDto>
)

@Serializable
data class IndexingToolSummaryDto(
    val toolKey: String,
    val displayName: String,
    val state: IndexingStateDto,
    /** ISO-8601 timestamp when current RUNNING started */
    val runningSince: String? = null,
    /** Cumulative processed items in current run (or last run if IDLE) */
    val processed: Int = 0,
    /** Cumulative errors in current run (or last run if IDLE) */
    val errors: Int = 0,
    /** Human readable last error if any */
    val lastError: String? = null,
    /** ISO-8601 timestamps of last run boundaries (if available) */
    val lastRunStartedAt: String? = null,
    val lastRunFinishedAt: String? = null,
)

@Serializable
data class IndexingToolDetailDto(
    val summary: IndexingToolSummaryDto,
    val items: List<IndexingItemDto>,
)

@Serializable
data class IndexingItemDto(
    /** ISO-8601 timestamp */
    val timestamp: String,
    /** "INFO" | "ERROR" | "PROGRESS" */
    val level: String,
    val message: String,
    val processedDelta: Int? = null,
    val errorsDelta: Int? = null,
)
