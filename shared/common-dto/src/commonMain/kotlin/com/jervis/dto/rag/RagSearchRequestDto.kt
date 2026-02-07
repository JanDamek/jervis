package com.jervis.dto.rag

import kotlinx.serialization.Serializable

@Serializable
data class RagSearchRequestDto(
    val clientId: String,
    val projectId: String? = null,
    val groupId: String? = null,
    val searchText: String,
    val filterKey: String? = null,
    val filterValue: String? = null,
    val maxChunks: Int = 5,
    val minSimilarityThreshold: Double = 0.15,
)
