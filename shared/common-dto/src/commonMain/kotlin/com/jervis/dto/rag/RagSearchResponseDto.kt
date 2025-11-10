package com.jervis.dto.rag

import kotlinx.serialization.Serializable

@Serializable
data class RagSearchResponseDto(
    val items: List<RagSearchItemDto>,
    val queriesProcessed: Int,
    val totalChunksFound: Int,
    val totalChunksFiltered: Int,
)
