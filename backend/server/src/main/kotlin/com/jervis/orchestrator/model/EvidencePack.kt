package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Evidence collected by ResearchAgent or during execution.
 *
 * Used by PlannerAgent (iteration 2+) and ReviewerAgent to assess completeness.
 */
@Serializable
data class EvidencePack(
    /** Individual evidence items */
    val items: List<EvidenceItem> = emptyList(),

    /** High-level summary of all evidence (for quick LLM consumption) */
    val summary: String = "",
) {
    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** Get evidence from specific source */
    fun fromSource(source: String): List<EvidenceItem> =
        items.filter { it.source.equals(source, ignoreCase = true) }

    /** Get high-confidence items only */
    fun highConfidence(threshold: Double = 0.7): List<EvidenceItem> =
        items.filter { it.confidence >= threshold }

    /** Combine summaries from all items */
    fun combinedSummary(): String =
        items.joinToString("\n") { "[${it.source}] ${it.content.take(200)}" }
}

/**
 * Single piece of evidence from a tool/agent.
 */
@Serializable
data class EvidenceItem(
    /** Source tool/agent name (e.g., "RAG", "GraphDB", "Joern", "logs", "aider", "openhands") */
    val source: String,

    /** Content of evidence (text, summary, code snippet, etc.) */
    val content: String,

    /** Confidence score 0.0-1.0 (higher = more reliable) */
    val confidence: Double = 1.0,

    /** Metadata (e.g., file paths, query used, timestamp) */
    val metadata: Map<String, String> = emptyMap(),
)
