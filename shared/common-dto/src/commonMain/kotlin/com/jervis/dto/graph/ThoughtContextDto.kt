package com.jervis.dto.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thought Map context from spreading activation.
 * Sent as THOUGHT_CONTEXT SSE event before LLM response.
 */
@Serializable
data class ThoughtContextDto(
    val thoughts: List<ActivatedThoughtDto> = emptyList(),
    val knowledge: List<AnchoredKnowledgeDto> = emptyList(),
    @SerialName("activated_thought_ids") val activatedThoughtIds: List<String> = emptyList(),
    @SerialName("activated_edge_ids") val activatedEdgeIds: List<String> = emptyList(),
)

@Serializable
data class ActivatedThoughtDto(
    val label: String = "",
    val type: String = "",
    val summary: String = "",
    val activation: Double = 0.0,
    @SerialName("is_entry") val isEntry: Boolean = false,
    val depth: Int = 0,
)

@Serializable
data class AnchoredKnowledgeDto(
    val label: String = "",
    val type: String = "",
    val description: String = "",
)
