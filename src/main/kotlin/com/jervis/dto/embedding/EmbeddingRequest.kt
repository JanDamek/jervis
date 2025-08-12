package com.jervis.dto.embedding

data class EmbeddingRequest(
    val model: String,
    val input: List<String> = emptyList(),
)
