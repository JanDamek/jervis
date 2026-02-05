package com.jervis.knowledgebase.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Single piece of evidence from a tool/agent.
 */
@Serializable
@SerialName("EvidenceItem")
data class EvidenceItem(
    val source: String,
    val content: String,
    val confidence: Double = 1.0,
    val metadata: Map<String, String> = emptyMap(),
)
