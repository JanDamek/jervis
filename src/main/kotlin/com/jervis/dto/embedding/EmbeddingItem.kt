package com.jervis.dto.embedding

data class EmbeddingItem(
    val `object`: String = "embedding",
    val embedding: List<Float>,
    val index: Int,
)
