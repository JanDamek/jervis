package com.jervis.knowledgebase.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Single piece of evidence from a tool/agent.
 */
@Serializable
@SerialName("EvidenceItem")
@LLMDescription(
    "Single piece of evidence from a specific source (RAG, GraphDB, Joern, logs, aider, openhands). Includes confidence score and metadata for traceability.",
)
data class EvidenceItem(
    @property:LLMDescription("Source tool/agent name (e.g., 'RAG', 'GraphDB', 'Joern', 'logs', 'aider', 'openhands')")
    val source: String,
    @property:LLMDescription("Content of evidence - text, summary, code snippet, analysis result, or findings")
    val content: String,
    @property:LLMDescription(
        "Confidence score 0.0-1.0 where 1.0 = highly reliable, 0.5 = uncertain, 0.0 = unreliable. Use this to prioritize evidence.",
    )
    val confidence: Double = 1.0,
    @property:LLMDescription(
        "Additional metadata (e.g., 'file_path': '/src/Main.kt', 'query': 'NTB purchases', 'timestamp': '2025-01-30T10:00:00Z')",
    )
    val metadata: Map<String, String> = emptyMap(),
)