package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Result of information ingestion into RAG and GraphDB.
 */
@Serializable
data class IngestResult(
    /** Whether the ingestion was successful */
    val success: Boolean,
    /** Summary of what was ingested and why */
    val summary: String,
    /** List of main node keys that were created/updated */
    val ingestedNodes: List<String> = emptyList(),
    /** Error message if success is false */
    val error: String? = null,
)
