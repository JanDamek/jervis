package com.jervis.knowledgebase.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Evidence collected by ResearchAgent or during execution.
 *
 * Used by PlannerAgent (iteration 2+) and ReviewerAgent to assess completeness.
 */
@Serializable
@SerialName("EvidencePack")
data class EvidencePack(
    val items: List<EvidenceItem> = emptyList(),
    val summary: String = "",
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 2000
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** Get evidence from specific source */
    fun fromSource(source: String): List<EvidenceItem> = items.filter { it.source.equals(source, ignoreCase = true) }

    /** Get high-confidence items only */
    fun highConfidence(threshold: Double = 0.7): List<EvidenceItem> = items.filter { it.confidence >= threshold }

    /** Combine summaries from all items */
    fun combinedSummary(): String = items.joinToString("\n") { "[${it.source}] ${it.content.take(MAX_CONTENT_LENGTH)}" }
}
