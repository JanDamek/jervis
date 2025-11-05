package com.jervis.dto.rag

import kotlinx.serialization.Serializable

@Serializable
data class RagSearchItemDto(
    val content: String,
    val score: Double,
    val metadata: Map<String, String> = emptyMap(),
)
