package com.jervis.knowledgebase.model

/**
 * Result of full document ingestion with routing hints.
 * Contains summary and actionability flags for qualification routing.
 */
data class FullIngestResult(
    val success: Boolean,
    val chunksCount: Int,
    val nodesCreated: Int,
    val edgesCreated: Int,
    val attachmentsProcessed: Int,
    val attachmentsFailed: Int,
    // Summary for routing decision
    val summary: String,
    val entities: List<String> = emptyList(),
    // Routing hints
    val hasActionableContent: Boolean = false,
    val suggestedActions: List<String> = emptyList(),
    // Scheduling hints (for three-way routing in qualifier)
    val hasFutureDeadline: Boolean = false,
    val suggestedDeadline: String? = null, // ISO-8601 datetime string
    val isAssignedToMe: Boolean = false,
    val urgency: String = "normal", // "urgent" | "normal" | "low"
    val error: String? = null,
)
