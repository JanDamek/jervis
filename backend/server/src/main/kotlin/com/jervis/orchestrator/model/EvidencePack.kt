package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Evidence collected by ResearchAgent or during execution.
 *
 * Used by PlannerAgent (iteration 2+) and ReviewerAgent to assess completeness.
 */
@Serializable
@SerialName("EvidencePack")
@LLMDescription("Collection of evidence gathered during research or execution. Contains individual findings from various sources (RAG, GraphDB, Joern, tools) with confidence scores and metadata. Used by PlannerAgent and ReviewerAgent to assess information completeness.")
data class EvidencePack(
    @property:LLMDescription("Individual evidence items from different sources - each item contains findings, confidence score, and metadata")
    val items: List<EvidenceItem> = emptyList(),

    @property:LLMDescription("High-level summary of all evidence combined - use this for quick overview before diving into individual items")
    val summary: String = "",
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 2000
    }

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
        items.joinToString("\n") { "[${it.source}] ${it.content.take(MAX_CONTENT_LENGTH)}" }
}

/**
 * Single piece of evidence from a tool/agent.
 */
@Serializable
@SerialName("EvidenceItem")
@LLMDescription("Single piece of evidence from a specific source (RAG, GraphDB, Joern, logs, aider, openhands). Includes confidence score and metadata for traceability.")
data class EvidenceItem(
    @property:LLMDescription("Source tool/agent name (e.g., 'RAG', 'GraphDB', 'Joern', 'logs', 'aider', 'openhands')")
    val source: String,

    @property:LLMDescription("Content of evidence - text, summary, code snippet, analysis result, or findings")
    val content: String,

    @property:LLMDescription("Confidence score 0.0-1.0 where 1.0 = highly reliable, 0.5 = uncertain, 0.0 = unreliable. Use this to prioritize evidence.")
    val confidence: Double = 1.0,

    @property:LLMDescription("Additional metadata (e.g., 'file_path': '/src/Main.kt', 'query': 'NTB purchases', 'timestamp': '2025-01-30T10:00:00Z')")
    val metadata: Map<String, String> = emptyMap(),
)
