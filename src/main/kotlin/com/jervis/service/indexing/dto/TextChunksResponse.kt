package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response from LLM containing multiple text chunks for class/method description.
 * Used for splitting detailed descriptions into smaller, RAG-optimized pieces.
 */
@Serializable
data class TextChunksResponse(
    val chunks: List<String> = listOf(""),
)
